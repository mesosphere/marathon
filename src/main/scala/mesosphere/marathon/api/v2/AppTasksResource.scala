package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import scala.{Array, Some}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.{Responses, EndpointsHelper}
import scala.concurrent.{Future, Await}
import org.apache.log4j.Logger
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.SECONDS
import mesosphere.marathon.api.v2.json.EnrichedTask


/**
 * @author Tobi Knaup
 */

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject()(service: MarathonSchedulerService,
                                 taskTracker: TaskTracker,
                                 healthCheckManager: HealthCheckManager) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson(@PathParam("appId") appId: String) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId).map { task =>
        EnrichedTask(appId, task, Await.result(healthCheckManager.status(appId, task.getId), Duration(2, SECONDS)))
      }
      Response.ok(Map("tasks" -> tasks)).build
    } else {
      Responses.unknownApp(appId)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String) =
    service.getApp(appId).fold(Responses.unknownApp(appId)) { app =>
      Response.ok(
        EndpointsHelper.appsToEndpointString(
          taskTracker,
          Seq(app),
          "\t"
        )
      ).build
    }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale") scale: Boolean = false) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)

      val toKill = Option(host).fold(tasks) { hostname =>
        tasks.filter(_.getHost == hostname || hostname == "*")
      }

      service.killTasks(appId, toKill, scale)
      Response.ok(Map("tasks" -> toKill)).build
    } else {
      Responses.unknownApp(appId)
    }
  }

  @DELETE
  @Path("{taskId}")
  @Timed
  def deleteOne(@PathParam("appId") appId: String,
                @PathParam("taskId") id: String,
                @QueryParam("scale") scale: Boolean = false) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      tasks.find(_.getId == id).fold(Responses.unknownTask(id)) { task =>
        service.killTasks(appId, Seq(task), scale)
        Response.ok(Map("task" -> task)).build
      }
    } else {
      Responses.unknownApp(appId)
    }
  }
}
