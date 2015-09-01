package mesosphere.marathon.api.v2

import java.util
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ MarathonMediaType, TaskKiller, EndpointsHelper, RestResource }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSchedulerService }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskState
import play.api.libs.json.Json

import scala.collection.IterableView
import scala.collection.JavaConverters._

@Path("v2/tasks")
class TasksResource @Inject() (
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    taskKiller: TaskKiller,
    val config: MarathonConf,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    taskIdUtil: TaskIdUtil) extends RestResource {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Timed
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String]): Response = {

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
      (appId, task) <- tasks
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

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(): Response = ok(EndpointsHelper.appsToEndpointString(
    taskTracker,
    result(groupManager.rootGroup()).transitiveApps.toSeq,
    "\t"
  ))

  @POST
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Timed
  @Path("delete")
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    body: Array[Byte]): Response = {
    val taskIds = (Json.parse(body) \ "ids").as[Set[String]]

    val groupedTasks = taskIds.flatMap { taskId =>
      val appId = try {
        taskIdUtil.appId(taskId)
      }
      catch {
        case e: MatchError => throw new BadRequestException(s"Invalid task id '$taskId'.")
      }

      taskTracker.fetchTask(appId, taskId)
    }.groupBy { x =>
      taskIdUtil.appId(x.getId)
    }

    groupedTasks.foreach {
      case (appId, tasks) =>
        def findToKill(appTasks: Set[MarathonTask]) = tasks.toSet
        if (scale) {
          taskKiller.killAndScale(appId, findToKill, force = true)
        }
        else {
          taskKiller.kill(appId, findToKill, force = true)
        }
    }

    // TODO: does anyone expect a response with all the deployment plans in case of scaling?
    Response.ok().build()
  }

  private def toTaskState(state: String): Option[TaskState] = state.toLowerCase match {
    case "running" => Some(TaskState.TASK_RUNNING)
    case "staging" => Some(TaskState.TASK_STAGING)
    case _         => None
  }
}
