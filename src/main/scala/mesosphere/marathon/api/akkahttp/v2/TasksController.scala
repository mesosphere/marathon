package mesosphere.marathon
package api.akkahttp.v2

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ MalformedQueryParamRejection, Rejection, Route }
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.api.akkahttp.Rejections.Message
import mesosphere.marathon.api.akkahttp._
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
import mesosphere.marathon.state.{ AppDefinition, PathId }

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
          pathEndOrSingleSlash {
            listTasks()
          }
        } ~
          (path("delete") & post) {
            deleteTasks()
          }
      }
    }
  }

  private def listTasks()(implicit identity: Identity): Route = {
    parameters("status".?, "status[]".as[String].*) { (statusParameter, statusParameters) =>
      val statuses = (statusParameter ++ statusParameters).to[Seq]
      onSuccess(enrichedTasks(statuses)) { tasks =>
        complete(TasksList(tasks).toRaml)
      }
    }
  }

  private def deleteTasks()(implicit identity: Identity): Route = {
    (entity(as[TasksToDelete])
      & parameter('force.as[Boolean].?(false))
      & parameter('scale.as[Boolean].?(false))
      & parameter('wipe.as[Boolean].?(false))) { (taskIds, force, scale, wipe) =>
        if (scale && wipe) {
          reject(MalformedQueryParamRejection("scale, wipe", "You cannot use scale and wipe at the same time."))
        } else {
          tryParseTaskIds(taskIds) match {
            case Left(rejection) => reject(rejection)
            case Right(parsedInstanceIds) =>
              val affectedApps = parsedInstanceIds.values.flatMap(appId => groupManager.app(appId))(collection.breakOut)
              if (!isAuthorized(affectedApps)) {
                reject(AuthDirectives.NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
              } else {
                val tasksToKill: Future[Map[PathId, Seq[Instance]]] = getTasksToKill(parsedInstanceIds, affectedApps)
                if (scale) {
                  onSuccess(killAndScale(tasksToKill, force)) {
                    case Left(rejection) => reject(rejection)
                    case Right(result) => complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(result.deploymentId)), result))
                  }
                } else {
                  onSuccess(kill(tasksToKill, wipe)) { tasks =>
                    complete(TasksList(tasks).toRaml)
                  }
                }
              }
          }
        }
      }
  }

  private def getTasksToKill(instanceIdsToAppId: Map[Id, PathId], affectedApps: IndexedSeq[AppDefinition])(implicit identity: Identity): Future[Map[PathId, Seq[Instance]]] = async {
    /**
      * Pods can't be killed using /tasks endpoint, only apps
      */
    def isNotPod(appId: PathId): Boolean = affectedApps.exists(app => app.id == appId)

    val maybeInstances = Future.sequence(instanceIdsToAppId.view
      .map { case (instanceId, _) => instanceTracker.instancesBySpec.map(_.instance(instanceId)) })
    val tasksToKill: Map[PathId, Seq[Instance]] = await(maybeInstances.map(i => i.flatten
      .groupBy(instance => instance.instanceId.runSpecId)
      .filter { case (appId, _) => isNotPod(appId) }
      .map { case (appId, instances) => appId -> instances.to[Seq] }(collection.breakOut)))

    tasksToKill
  }
  private def isAuthorized(apps: Iterable[AppDefinition])(implicit identity: Identity): Boolean = apps.forall(app => authorizer.isAuthorized(identity, UpdateRunSpec, app))
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
  private def kill(toKillFuture: Future[Map[PathId, Seq[Instance]]], wipe: Boolean)(implicit identity: Identity): Future[Seq[EnrichedTask]] = async {
    val toKill = await(toKillFuture)

    val killedTasks = await(Future.sequence(toKill
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
  def killAndScale(toKillFuture: Future[Map[PathId, Seq[Instance]]], force: Boolean)(implicit identity: Identity): Future[Either[Rejection, DeploymentResult]] = async {
    val toKill = await(toKillFuture)
    if (toKill.isEmpty) {
      Left(Rejections.BadRequest(Message("No tasks to kill and scale.")))
    } else {
      val deploymentPlan = await(taskKiller.killAndScale(toKill, force))
      Right(DeploymentResult(deploymentPlan.id, deploymentPlan.version.toOffsetDateTime))
    }
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
