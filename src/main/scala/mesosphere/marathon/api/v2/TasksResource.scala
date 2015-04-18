package mesosphere.marathon.api.v2

import java.util
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }
import javax.inject.Inject
import com.sun.jersey.api.Responses
import mesosphere.marathon.api.{ EndpointsHelper, RestResource }
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSchedulerService }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import org.apache.log4j.Logger
import com.codahale.metrics.annotation.Timed
import org.apache.mesos.Protos.TaskState
import play.api.libs.json.Json

import scala.collection.IterableView
import scala.collection.JavaConverters._

@Path("v2/tasks")
class TasksResource @Inject() (
    service: MarathonSchedulerService,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    val config: MarathonConf,
    taskIdUtil: TaskIdUtil) extends RestResource {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson(
    @QueryParam("status") status: String,
    @QueryParam("status[]") statuses: util.List[String]): Response = {

    if (status != null) {
      statuses.add(status)
    }
    val statusSet = statuses.asScala.flatMap(toTaskState).toSet

    val tasks = taskTracker.list.values.view.flatMap { app =>
      app.tasks.view.map(t => app.appName -> t)
    }

    val appIds = taskTracker.list.keySet

    val appToPorts = appIds.map { appId =>
      appId -> service.getApp(appId).map(_.servicePorts()).getOrElse(Nil)
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

    ok(Map(
      "tasks" -> enrichedTasks
    ))
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(): Response = ok(EndpointsHelper.appsToEndpointString(
    taskTracker,
    service.listApps().toSeq,
    "\t"
  ))

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Timed
  @Path("/delete")
  def killTasks(
    @QueryParam("scale")@DefaultValue("false") scale: Boolean,
    body: Array[Byte]): Response = {
    val taskIds = (Json.parse(body) \ "ids").as[Seq[String]]

    val groupedTasks = taskIds.flatMap { taskId =>
      val appId = try {
        taskIdUtil.appId(taskId)
      }
      catch {
        case e: MatchError => throw new BadRequestException(s"Invalid task id '$taskId'.")
      }

      taskTracker.fetchTaskById(appId, taskId)
    }.groupBy { x =>
      taskIdUtil.appId(x.getId)
    }

    groupedTasks.foreach {
      case (appId, tasks) =>
        service.killTasks(appId, tasks, scale)
    }

    Response.ok().build()
  }

  private def toTaskState(state: String): Option[TaskState] = state.toLowerCase match {
    case "running" => Some(TaskState.TASK_RUNNING)
    case "staging" => Some(TaskState.TASK_STAGING)
    case _         => None
  }
}
