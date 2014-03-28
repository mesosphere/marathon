package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import scala.{Array, Some}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.{Responses, EndpointsHelper}
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Await
import java.util.logging.Logger
import javax.ws.rs.core.Response.Status


/**
 * @author Tobi Knaup
 */

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject()(service: MarathonSchedulerService,
                                 taskTracker: TaskTracker) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson(@PathParam("appId") appId: String) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      Response.ok(Map("tasks" -> tasks)).build
    } else {
      Responses.unknownApp(appId)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String) = {
    service.getApp(appId) match {
      case Some(app) => Response.ok(
        EndpointsHelper.appsToEndpointString(
          taskTracker,
          Seq(app),
          "\t"
        )
      ).build
      case None => Responses.unknownApp(appId)
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale") scale: Boolean = false) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)

      val toKill = Option(host) match {
        case Some(hostname) => tasks.filter(_.getHost == hostname || hostname == "*")
        case _ => tasks
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
      tasks.find(_.getId == id) match {
        case Some(task) => {
          service.killTasks(appId, Seq(task), scale)
          Response.ok(Map("task" -> task)).build
        }
        case None => Responses.unknownTask(id)
      }
    } else {
      Responses.unknownApp(appId)
    }
  }
}
