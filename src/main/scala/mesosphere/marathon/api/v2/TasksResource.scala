package mesosphere.marathon.api.v2

import java.util
import javax.inject.Inject
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ EndpointsHelper, MarathonMediaType, TaskKiller, _ }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, KillTask }
import mesosphere.marathon.state.{ GroupManager, PathId }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSchedulerService }
import mesosphere.util.ThreadPoolContext
import org.apache.mesos.Protos.TaskState
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.IterableView
import scala.collection.JavaConverters._
import scala.concurrent.Future

@Path("v2/tasks")
class TasksResource @Inject() (
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    taskKiller: TaskKiller,
    val config: MarathonConf,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    taskIdUtil: TaskIdUtil,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends AuthResource {

  val log = LoggerFactory.getLogger(getClass.getName)
  implicit val ec = ThreadPoolContext.context

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Timed
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String],
    @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {

    doIfAuthenticated(req, resp) { implicit identity =>
      //scalastyle:off null
      if (status != null) {
        statuses.add(status)
      }
      //scalastyle:on
      val statusSet = statuses.asScala.flatMap(toTaskState).toSet

      val tasks = taskTracker.list.values.view.flatMap { app =>
        app.tasks.view.map(t => app.appName -> t)
      }

      val appIds = taskTracker.list.keySet

      val appToPorts = appIds.map { appId =>
        appId -> service.getApp(appId).map(_.servicePorts).getOrElse(Nil)
      }.toMap

      val health = appIds.flatMap { appId =>
        result(healthCheckManager.statuses(appId))
      }.toMap

      val enrichedTasks: IterableView[EnrichedTask, Iterable[_]] = for {
        (appId, task) <- tasks if isAllowedToView(appId)
        if statusSet.isEmpty || statusSet(task.getStatus.getState)
      } yield {
        EnrichedTask(
          appId,
          task,
          health.getOrElse(task.getId, Nil),
          appToPorts.getOrElse(appId, Nil)
        )
      }

      ok(jsonObjString(
        "tasks" -> enrichedTasks
      ))
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthenticated(req, resp) { implicit identity =>
      ok(EndpointsHelper.appsToEndpointString(
        taskTracker,
        result(groupManager.rootGroup()).transitiveApps.toSeq.filter(app => isAllowedToView(app.id)),
        "\t"
      ))
    }
  }

  @POST
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Timed
  @Path("delete")
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    @QueryParam("force")@DefaultValue("false") force: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {

    val taskIds = (Json.parse(body) \ "ids").as[Set[String]]
    val taskToAppIds = taskIds.map { id =>
      try { id -> taskIdUtil.appId(id) }
      catch { case e: MatchError => throw new BadRequestException(s"Invalid task id '$id'.") }
    }.toMap

    doIfAuthorized(req, resp, KillTask, taskToAppIds.values.toSeq: _*) { implicit identity =>

      def scaleAppWithKill(toKill: Map[PathId, Set[MarathonTask]]): Response = {
        deploymentResult(result(taskKiller.killAndScale(toKill, force)))
      }

      def killTasks(toKill: Map[PathId, Set[MarathonTask]]): Response = {
        val killed = result(Future.sequence(toKill.map {
          case (appId, tasks) => taskKiller.kill(appId, _ => tasks)
        })).flatten
        ok(jsonObjString("tasks" -> killed.map(task => EnrichedTask(taskIdUtil.appId(task.getId), task, Seq.empty))))
      }

      val taskByApps = taskToAppIds
        .flatMap { case (taskId, appId) => taskTracker.getTask(appId, taskId) }
        .groupBy { x => taskIdUtil.appId(x.getId) }
        .map{ case (app, tasks) => app -> tasks.toSet }

      if (scale) scaleAppWithKill(taskByApps) else killTasks(taskByApps)
    }
  }

  private def toTaskState(state: String): Option[TaskState] = state.toLowerCase match {
    case "running" => Some(TaskState.TASK_RUNNING)
    case "staging" => Some(TaskState.TASK_STAGING)
    case _         => None
  }
}
