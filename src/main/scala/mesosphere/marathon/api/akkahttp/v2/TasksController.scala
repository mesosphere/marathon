package mesosphere.marathon
package api.akkahttp.v2

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.MediaTypes.`text/plain`
import akka.http.scaladsl.model.{ MediaTypes, StatusCodes }
import akka.http.scaladsl.server.{ Rejection, Route }
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.EndpointsHelper.ListTasks
import mesosphere.marathon.api.{ EndpointsHelper, TaskKiller }
import mesosphere.marathon.api.akkahttp.Rejections.Message
import mesosphere.marathon.api.akkahttp.{ Controller, _ }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, _ }
import mesosphere.marathon.raml.{ AnyToRaml, DeploymentResult, EnrichedTasksList, Reads, Writes }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.Implicits._

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class TasksController(
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    taskKiller: TaskKiller,
    val electionService: ElectionService)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val materializer: Materializer
) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._
  import mesosphere.marathon.raml.EnrichedTaskConversion._

  override val route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        get {
          (accepts(MediaTypes.`text/plain`) & pathEndOrSingleSlash) {
            listTasksTxt()
          } ~
            (accepts(MediaTypes.`application/json`) & pathEndOrSingleSlash) {
              listTasksJson()
            } ~
            acceptsAnything {
              listTasksJson() // when no accept header present, json is the default choice
            }
        } ~
          (path("delete") & post) {
            deleteTasks()
          }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def listTasksTxt()(implicit identity: Identity): Route = {
    onSuccess(async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      val apps = groupManager.rootGroup().transitiveApps.filterAs(app => authorizer.isAuthorized(identity, ViewRunSpec, app))(collection.breakOut)
      ListTasks(instancesBySpec, apps)
    }) { data =>
      complete(data)
    }
  }

  private def listTasksJson()(implicit identity: Identity): Route = {
    parameters("status".?, "status[]".as[String].*) { (statusParameter, statusParameters) =>
      val statuses = (statusParameter ++ statusParameters).to[Seq]
      onSuccess(enrichedTasks(statuses)) { tasks =>
        complete(TasksList(tasks).toRaml)
      }
    }
  }

  private def deleteTasks()(implicit identity: Identity): Route = {
    import AppsDirectives._

    (entity(as[TasksToDelete])
      & parameter('force.as[Boolean].?(false))
      & extractTaskKillingMode) { (taskIds, force, taskKillingMode) =>
        tryParseTaskIds(taskIds) match {
          case Left(rejection) => reject(rejection)
          case Right(instanceIdsToAppId) =>
            if (!isAuthorized(instanceIdsToAppId.values)) {
              reject(AuthDirectives.NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
            } else {
              val tasksByAppId: Future[Map[PathId, Seq[Instance]]] = getTasksByAppId(instanceIdsToAppId)

              if (taskKillingMode == TaskKillingMode.Scale) {
                onSuccess(killAndScale(tasksByAppId, force)) { deploymentResult =>
                  complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(deploymentResult.deploymentId)), deploymentResult))
                }
              } else {
                onSuccess(kill(tasksByAppId, instanceIdsToAppId, taskKillingMode == TaskKillingMode.Wipe)) { tasks =>
                  complete(TasksList(tasks).toRaml)
                }
              }
            }
        }
      }
  }
  private def getTasksByAppId(instanceIdsToAppId: Map[Id, PathId]): Future[Map[PathId, Seq[Instance]]] = {
    val maybeInstances = Future.sequence(instanceIdsToAppId.view
      .map { case (instanceId, _) => instanceTracker.instancesBySpec.map(_.instance(instanceId)) })
    maybeInstances.map(i => i.flatten
      .groupBy(instance => instance.instanceId.runSpecId)
      .map { case (appId, instances) => appId -> instances.to[Seq] }(collection.breakOut))
  }
  private def isAuthorized(appIds: Iterable[PathId])(implicit identity: Identity): Boolean = appIds.forall(id => authorizer.isAuthorized(identity, UpdateRunSpec, id))
  private def tryParseTaskIds(taskIds: TasksToDelete): Either[Rejection, Map[Id, PathId]] = {
    val maybeInstanceIdToAppId: Try[Map[Id, PathId]] = Try(taskIds.ids.map { id =>
      try { Task.Id(id).instanceId -> Task.Id.runSpecId(id) }
      catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'. [${e.getMessage}]") }
    }(collection.breakOut))

    maybeInstanceIdToAppId match {
      case Success(result) => Right(result)
      case Failure(e: BadRequestException) => Left(Rejections.BadRequest(Message(e.getMessage)))
      case Failure(e) => throw e
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def enrichedTasks(statuses: Seq[String])(implicit identity: Identity): Future[Seq[EnrichedTask]] = async {
    val conditionSet: Set[Condition] = statuses.flatMap(toCondition)(collection.breakOut)

    val instancesBySpec = await(instanceTracker.instancesBySpec)

    val instances: Iterable[(PathId, Instance)] = instancesBySpec.instancesMap.values.flatMap { appTasks =>
      appTasks.instances.map(i => appTasks.specId -> i)
    }
    val appIds: Set[PathId] = instancesBySpec.allSpecIdsWithInstances
    val appIdsToApps = groupManager.apps(appIds)

    val appToPorts: Map[PathId, Seq[Int]] = appIdsToApps.map {
      case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
    }

    val instancesHealth = await(
      Source(appIds)
        .mapAsyncUnordered(4)(appId => healthCheckManager.statuses(appId))
        .runFold(Map[Id, Seq[Health]]())(_ ++ _)
    )

    def isInterestingInstance(condition: Condition) = conditionSet.isEmpty || conditionSet(condition)
    def isAuthorized(appId: PathId): Boolean = appIdsToApps(appId).fold(false)(id => authorizer.isAuthorized(identity, ViewRunSpec, id))

    instances
      .filter {
        case (appId, instance) => isAuthorized(appId) && isInterestingInstance(instance.state.condition)
      }
      .flatMap {
        case (appId, instance) => instance.tasksMap.values.map(t => EnrichedTask(
          appId,
          t,
          instance.agentInfo,
          instancesHealth.getOrElse(instance.instanceId, Nil),
          appToPorts.getOrElse(appId, Nil)
        ))
      }.to[Seq]
  }

  implicit val listTasksMarshaller: ToEntityMarshaller[ListTasks] =
    Marshaller
      .stringMarshaller(`text/plain`)
      .compose(data => EndpointsHelper.appsToEndpointString(data))

  private def toCondition(state: String): Option[Condition] = state.toLowerCase match {
    case "running" => Some(Condition.Running)
    case "staging" => Some(Condition.Staging)
    case _ => None
  }

  /**
    * Performs the task kill on the provided taskIds (without scale). Delegates the job to TaskKiller.
    * @return list of killed tasks
    */
  @SuppressWarnings(Array("all")) // async/await
  private def kill(toKillFuture: Future[Map[PathId, Seq[Instance]]], tasksIdToAppId: Map[Id, PathId], wipe: Boolean)(implicit identity: Identity): Future[Seq[EnrichedTask]] = async {
    val toKill = await(toKillFuture)
    val affectedApps = tasksIdToAppId.values.flatMap(appId => groupManager.app(appId))(collection.breakOut)

    val killedTasks = await(Future.sequence(toKill
      .filter { case (appId, _) => affectedApps.exists(app => app.id == appId) }
      .map { case (appId, instances) => taskKiller.kill(appId, _ => instances, wipe) }))
      .flatten

    killedTasks.flatMap { instance =>
      instance.tasksMap.valuesIterator.map { task =>
        EnrichedTask(task.runSpecId, task, instance.agentInfo, Seq.empty)
      }
    }.to[Seq]
  }

  /**
    * Performs kill and scale on provided list of task ids. Delegates the job to TaskKiller.
    * @return new deployment created as the result of scale operation
    */
  @SuppressWarnings(Array("all")) // async/await
  def killAndScale(tasksByAppIdFuture: Future[Map[PathId, Seq[Instance]]], force: Boolean)(implicit identity: Identity): Future[DeploymentResult] = async {
    val tasksByAppId = await(tasksByAppIdFuture)
    val deploymentPlan = await(taskKiller.killAndScale(tasksByAppId, force))
    DeploymentResult(deploymentPlan.id, deploymentPlan.version.toOffsetDateTime)
  }

  case class TasksList(tasks: Seq[EnrichedTask])
  implicit val tasksListWrite: Writes[TasksList, EnrichedTasksList] = Writes { tasksList =>
    EnrichedTasksList(tasksList.tasks.map(_.toRaml))
  }

  implicit val deleteTasksReader: Reads[raml.DeleteTasks, TasksToDelete] = Reads {
    tasksToDelete => TasksToDelete(tasksToDelete.ids.toSet)
  }
  case class TasksToDelete(ids: Set[String])
  implicit def tasksToDeleteUnmarshaller(
    implicit
    um: FromEntityUnmarshaller[raml.DeleteTasks],
    reader: raml.Reads[raml.DeleteTasks, TasksToDelete]): FromEntityUnmarshaller[TasksToDelete] = {
    um.map { ent => reader.read(ent) }
  }
}
