package mesosphere.marathon
package api.v2

import java.util
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ EndpointsHelper, MarathonMediaType, TaskKiller, _ }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream._
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

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
  @Timed
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    Option(status).map(statuses.add)
    val conditionSet: Set[Condition] = statuses.flatMap(toTaskState)(collection.breakOut)

    val instancesBySpec = instanceTracker.instancesBySpecSync

    val instances = instancesBySpec.instancesMap.values.view.flatMap { appTasks =>
      appTasks.instances.map(i => appTasks.specId -> i)
    }

    val appIds = instancesBySpec.allSpecIdsWithInstances

    val appIdsToApps: Map[PathId, Option[AppDefinition]] =
      appIds.map(appId => appId -> result(groupManager.app(appId)))(collection.breakOut)

    val appToPorts = appIdsToApps.map {
      case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
    }

    val health: Map[Instance.Id, Seq[Health]] = appIds.flatMap { appId =>
      result(healthCheckManager.statuses(appId))
    }(collection.breakOut)

    val enrichedTasks: Iterable[EnrichedTask] = instances.flatMap {
      case (appId, instance) =>
        val app = appIdsToApps(appId)
        if (isAuthorized(ViewRunSpec, app) && (conditionSet.isEmpty || conditionSet(instance.state.condition))) {
          instance.tasksMap.values.map { task =>
            EnrichedTask(
              appId,
              task,
              health.getOrElse(instance.instanceId, Nil),
              appToPorts.getOrElse(appId, Nil)
            )
          }
        } else {
          None
        }
    }.force

    ok(jsonObjString(
      "tasks" -> enrichedTasks
    ))
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    ok(EndpointsHelper.appsToEndpointString(
      instanceTracker,
      result(groupManager.rootGroup()).transitiveApps.filterAs(app => isAuthorized(ViewRunSpec, app))(collection.breakOut),
      "\t"
    ))
  }

  @POST
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Timed
  @Path("delete")
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    @QueryParam("force")@DefaultValue("false") force: Boolean,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    val taskIds = (Json.parse(body) \ "ids").as[Set[String]]
    val tasksToAppId: Map[String, PathId] = taskIds.map { id =>
      try { id -> Task.Id.runSpecId(id) }
      catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'.") }
    }(collection.breakOut)
    def scaleAppWithKill(toKill: Map[PathId, Seq[Instance]]): Response = {
      deploymentResult(result(taskKiller.killAndScale(toKill, force)))
    }

    def killTasks(toKill: Map[PathId, Seq[Instance]]): Response = {
      val affectedApps = tasksToAppId.values.flatMap(appId => result(groupManager.app(appId))).toSeq
      // FIXME (gkleiman): taskKiller.kill a few lines below also checks authorization, but we need to check ALL before
      // starting to kill tasks
      affectedApps.foreach(checkAuthorization(UpdateRunSpec, _))

      val killed = result(Future.sequence(toKill.map {
        case (appId, instances) => taskKiller.kill(appId, _ => instances, wipe)
      })).flatten
      ok(jsonObjString("tasks" -> killed.flatMap(_.tasksMap.values).map(task => EnrichedTask(task.runSpecId, task, Seq.empty))))
    }

    val tasksByAppId: Map[PathId, Seq[Instance]] = tasksToAppId.view
      .flatMap { case (taskId, appId) => instanceTracker.instancesBySpecSync.instance(Task.Id(taskId).instanceId) }
      .groupBy { instance => instance.instanceId.runSpecId }
      .map { case (appId, instances) => appId -> instances.to[Seq] }(collection.breakOut)

    if (scale) scaleAppWithKill(tasksByAppId)
    else killTasks(tasksByAppId)
  }

  private def toTaskState(state: String): Option[Condition] = state.toLowerCase match {
    case "running" => Some(Condition.Running)
    case "staging" => Some(Condition.Staging)
    case _ => None
  }
}
