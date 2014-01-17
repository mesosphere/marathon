package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import scala.{Array, Some}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import java.util.logging.Logger

/**
 * @author Tobi Knaup
 */

@Produces(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject()(service: MarathonSchedulerService,
                                 taskTracker: TaskTracker) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      tasks
    } else {
      Response.status(Response.Status.NOT_FOUND).build
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale") scale: Boolean = false) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val toKill = tasks.filter(_.getHost == host || host == "*")

      service.killTasks(appId, toKill, scale)
      Response.ok(Map("tasks" -> toKill)).build
    } else {
      Response.status(Response.Status.NOT_FOUND).build
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
        case None => Response.status(Response.Status.NOT_FOUND).build
      }
    } else {
      Response.status(Response.Status.NOT_FOUND).build
    }
  }
}
