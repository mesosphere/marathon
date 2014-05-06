package mesosphere.marathon.api.v1

import javax.ws.rs._
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.Response.Status

@Path("v1/tasks")
@Produces(Array(MediaType.APPLICATION_JSON))
class TasksResource @Inject()(
  service: MarathonSchedulerService,
  taskTracker: TaskTracker) {

  @GET
  @Timed
  def list = taskTracker.list

  @POST
  @Path("kill")
  @Timed
  def killTasks(@QueryParam("appId") appId: String,
                @QueryParam("host") host: String,
                @QueryParam("id") id: String = "*",
                @QueryParam("scale") scale: Boolean = false) = {
    val tasks = taskTracker.get(appId)
    val toKill = tasks.filter( x =>
      x.getHost == host || x.getId == id || host == "*"
    )

    if (toKill.size == 0)
      Response.status(Status.NOT_FOUND).entity(Map("message" -> "No tasks matched your filters")).build
    else
      service.killTasks(appId, toKill, scale)
  }
}
