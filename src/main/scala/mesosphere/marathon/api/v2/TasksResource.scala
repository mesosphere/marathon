package mesosphere.marathon.api.v2

import java.util
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ EndpointsHelper, MarathonMediaType, TaskKiller, _ }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.{ BadRequestException, MarathonConf }
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.IterableView
import scala.collection.JavaConverters._
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
    val statusSet = statuses.asScala.flatMap(toTaskState).toSet

    val taskList = instanceTracker.instancesBySpecSync

    val tasks = taskList.instancesMap.values.view.flatMap { appTasks =>
      appTasks.instances.flatMap(_.tasks).view.map(t => appTasks.specId -> t)
    }

    val appIds = taskList.allSpecIdsWithInstances

    val appIdsToApps = appIds.map(appId => appId -> result(groupManager.app(appId))).toMap

    val appToPorts = appIdsToApps.map {
      case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
    }

    val health = appIds.flatMap { appId =>
      result(healthCheckManager.statuses(appId))
    }.toMap

    val enrichedTasks: IterableView[EnrichedTask, Iterable[_]] = for {
      (appId, task) <- tasks
      app <- appIdsToApps(appId)
      if isAuthorized(ViewRunSpec, app)
      if statusSet.isEmpty || statusSet(task.status.taskStatus)
    } yield {
      EnrichedTask(
        appId,
        task,
        health.getOrElse(task.taskId, Nil),
        appToPorts.getOrElse(appId, Nil)
      )
    }

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
      result(groupManager.rootGroup()).transitiveApps.toSeq.filter(app => isAuthorized(ViewRunSpec, app)),
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
    val tasksToAppId = taskIds.map { id =>
      try { id -> Task.Id.runSpecId(id) }
      catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'.") }
    }.toMap

    def scaleAppWithKill(toKill: Map[PathId, Iterable[Instance]]): Response = {
      deploymentResult(result(taskKiller.killAndScale(toKill, force)))
    }

    def killTasks(toKill: Map[PathId, Iterable[Instance]]): Response = {
      val affectedApps = tasksToAppId.values.flatMap(appId => result(groupManager.app(appId))).toSeq
      // FIXME (gkleiman): taskKiller.kill a few lines below also checks authorization, but we need to check ALL before
      // starting to kill tasks
      affectedApps.foreach(checkAuthorization(UpdateRunSpec, _))

      val killed = result(Future.sequence(toKill.map {
        case (appId, instances) => taskKiller.kill(appId, _ => instances, wipe)
      })).flatten
      ok(jsonObjString("tasks" -> killed.flatMap(_.tasks).map(task => EnrichedTask(task.runSpecId, task, Seq.empty))))
    }

    val tasksByAppId = tasksToAppId
      .flatMap { case (taskId, appId) => instanceTracker.instancesBySpecSync.instance(Task.Id(taskId).instanceId) }
      .groupBy { instance => instance.instanceId.runSpecId }
      .map{ case (appId, instances) => appId -> instances }

    if (scale) scaleAppWithKill(tasksByAppId)
    else killTasks(tasksByAppId)
  }

  private def toTaskState(state: String): Option[InstanceStatus] = state.toLowerCase match {
    case "running" => Some(InstanceStatus.Running)
    case "staging" => Some(InstanceStatus.Staging)
    case _ => None
  }
}
