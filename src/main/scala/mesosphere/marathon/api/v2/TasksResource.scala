package mesosphere.marathon
package api.v2

import java.util
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}

import mesosphere.marathon.api.EndpointsHelper.ListTasks
import mesosphere.marathon.api.{EndpointsHelper, TaskKiller, _}
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{Health, HealthCheckManager}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec}
import mesosphere.marathon.raml.AnyToRaml
import mesosphere.marathon.raml.Task._
import mesosphere.marathon.raml.TaskConversion._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.Implicits._
import play.api.libs.json.Json

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

@Path("v2/tasks")
class TasksResource @Inject() (
    instanceTracker: InstanceTracker,
    taskKiller: TaskKiller,
    val config: MarathonConf,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer)(implicit val executionContext: ExecutionContext) extends AuthResource {

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    Option(status).map(statuses.add)
    val conditionSet: Set[Condition] = statuses.flatMap(toTaskState)(collection.breakOut)

    val futureEnrichedTasks = async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)

      val appIds: Set[PathId] = instancesBySpec.allSpecIdsWithInstances

      val appIdsToApps = groupManager.apps(appIds)

      val appToPorts: Map[PathId, Seq[Int]] = appIdsToApps.map {
        case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
      }

      val health = await(
        Future.sequence(appIds.map { appId =>
          healthCheckManager.statuses(appId)
        })).foldLeft(Map[Id, Seq[Health]]())(_ ++ _)

      val enrichedTasks: Iterable[Iterable[EnrichedTask]] = for {
        (appId, instances) <- instancesBySpec.instancesMap
        instance <- instances.instances
        app <- appIdsToApps(appId)
        if (isAuthorized(ViewRunSpec, app) && (conditionSet.isEmpty || conditionSet(instance.state.condition)))
      } yield {
        EnrichedTask.fromInstance(
          instance,
          healthCheckResults = health.getOrElse(instance.instanceId, Nil),
          servicePorts = appToPorts.getOrElse(appId, Nil)
        )
      }
      enrichedTasks.flatten
    }

    val enrichedTasks: Iterable[EnrichedTask] = result(futureEnrichedTasks)
    ok(jsonObjString(
      "tasks" -> enrichedTasks.toIndexedSeq.toRaml
    ))
  }

  @GET
  @Produces(Array(RestResource.TEXT_PLAIN_LOW))
  def indexTxt(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    result(async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      val rootGroup = groupManager.rootGroup()
      val appsToEndpointString = EndpointsHelper.appsToEndpointString(
        ListTasks(instancesBySpec, rootGroup.transitiveApps.filterAs(app => isAuthorized(ViewRunSpec, app))(collection.breakOut))
      )
      ok(appsToEndpointString)
    })
  }

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("delete")
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    @QueryParam("force")@DefaultValue("false") force: Boolean,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    val taskIds = (Json.parse(body) \ "ids").as[Set[String]]
    val tasksIdToAppId: Map[Instance.Id, PathId] = taskIds.map { id =>
      try {
        val taskId = Task.Id.parse(id)
        taskId.instanceId -> taskId.instanceId.runSpecId
      } catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'. [${e.getMessage}]") }
    }(collection.breakOut)

    def scaleAppWithKill(toKill: Map[PathId, Seq[Instance]]): Future[Response] = async {
      val killAndScale = await(taskKiller.killAndScale(toKill, force))
      deploymentResult(killAndScale)
    }

    def doKillTasks(toKill: Map[PathId, Seq[Instance]]): Future[Response] = async {
      val affectedApps = tasksIdToAppId.values.flatMap(appId => groupManager.app(appId))(collection.breakOut)
      // FIXME (gkleiman): taskKiller.kill a few lines below also checks authorization, but we need to check ALL before
      // starting to kill tasks
      affectedApps.foreach(checkAuthorization(UpdateRunSpec, _))
      val killedInstances = await(Future.sequence(toKill
        .filter { case (appId, _) => affectedApps.exists(app => app.id == appId) }
        .map {
          case (appId, instances) => taskKiller.kill(appId, _ => instances, wipe)
        })).flatten
      val killedTasks = killedInstances.flatMap { i => EnrichedTask.fromInstance(i).map(_.toRaml) }
      ok(jsonObjString("tasks" -> killedTasks))
    }

    val futureResponse = async {
      val maybeInstances: Iterable[Option[Instance]] = await(Future.sequence(tasksIdToAppId.view
        .map { case (taskId, _) => instanceTracker.instancesBySpec.map(_.instance(taskId)) }))
      val tasksByAppId: Map[PathId, Seq[Instance]] = maybeInstances.flatten
        .groupBy(instance => instance.instanceId.runSpecId)
        .map { case (appId, instances) => appId -> instances.to[Seq] }(collection.breakOut)
      val response =
        if (scale) scaleAppWithKill(tasksByAppId)
        else doKillTasks(tasksByAppId)
      await(response)
    }
    result(futureResponse)
  }

  private def toTaskState(state: String): Option[Condition] = state.toLowerCase match {
    case "running" => Some(Condition.Running)
    case "staging" => Some(Condition.Staging)
    case _ => None
  }
}
