package mesosphere.marathon
package api.v2

import java.util
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ EndpointsHelper, MarathonMediaType, TaskKiller, _ }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream.Implicits._
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }

@Path("v2/tasks")
class TasksResource @Inject() (
    instanceTracker: InstanceTracker,
    taskKiller: TaskKiller,
    val config: MarathonConf,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends AuthResource {

  val log = LoggerFactory.getLogger(getClass.getName)
  implicit val ec = ExecutionContext.Implicits.global

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @SuppressWarnings(Array("all")) /* async/await */
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    Option(status).map(statuses.add)
    val conditionSet: Set[Condition] = statuses.flatMap(toTaskState)(collection.breakOut)

    val futureEnrichedTasks = async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)

      val instances = instancesBySpec.instancesMap.values.view.flatMap { appTasks =>
        appTasks.instances.map(i => appTasks.specId -> i)
      }
      val appIds = instancesBySpec.allSpecIdsWithInstances

      //TODO: Move to GroupManager.
      val appIdsToApps: Map[PathId, Option[AppDefinition]] =
        appIds.map(appId => appId -> groupManager.app(appId))(collection.breakOut)

      val appToPorts = appIdsToApps.map {
        case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
      }

      val health = await(
        Future.sequence(appIds.map { appId =>
          healthCheckManager.statuses(appId)
        })).foldLeft(Map[Id, Seq[Health]]())(_ ++ _)

      instances.flatMap {
        case (appId, instance) =>
          val app = appIdsToApps(appId)
          if (isAuthorized(ViewRunSpec, app) && (conditionSet.isEmpty || conditionSet(instance.state.condition))) {
            instance.tasksMap.values.map { task =>
              EnrichedTask(
                appId,
                task,
                instance.agentInfo,
                health.getOrElse(instance.instanceId, Nil),
                appToPorts.getOrElse(appId, Nil)
              )
            }
          } else {
            None
          }
      }.force
    }

    val enrichedTasks: Iterable[EnrichedTask] = result(futureEnrichedTasks)
    ok(jsonObjString(
      "tasks" -> enrichedTasks
    ))
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @SuppressWarnings(Array("all")) /* async/await */
  def indexTxt(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    result(async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      val rootGroup = groupManager.rootGroup()
      val appsToEndpointString = EndpointsHelper.appsToEndpointString(
        instancesBySpec,
        rootGroup.transitiveApps.filterAs(app => isAuthorized(ViewRunSpec, app))(collection.breakOut)
      )
      ok(appsToEndpointString)
    })
  }

  @POST
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("delete")
  @SuppressWarnings(Array("all")) /* async/await */
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    @QueryParam("force")@DefaultValue("false") force: Boolean,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    val taskIds = (Json.parse(body) \ "ids").as[Set[String]]
    val tasksIdToAppId: Map[Instance.Id, PathId] = taskIds.map { id =>
      try { Task.Id(id).instanceId -> Task.Id.runSpecId(id) }
      catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'. [${e.getMessage}]") }
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
      val killed = await(Future.sequence(toKill
        .filter { case (appId, _) => affectedApps.exists(app => app.id == appId) }
        .map {
          case (appId, instances) => taskKiller.kill(appId, _ => instances, wipe)
        })).flatten
      ok(jsonObjString("tasks" -> killed.flatMap { instance =>
        instance.tasksMap.valuesIterator.map { task =>
          EnrichedTask(task.runSpecId, task, instance.agentInfo, Seq.empty)
        }
      }))
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
