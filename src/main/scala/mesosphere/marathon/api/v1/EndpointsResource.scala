package mesosphere.marathon.api.v1

import javax.ws.rs.{GET, Produces, Path}
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker

/**
 * @author Tobi Knaup
 */

@Path("v1/endpoints")
class EndpointsResource @Inject()(
    schedulerService: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  def endpoints() = {
    val sb = new StringBuilder
    for (app <- schedulerService.listApps()) {
      sb.append(app.id).append(" ").append(app.port).append(" ")

      for (task <- taskTracker.get(app.id)) {
        sb.append(task.getHost).append(":").append(task.getPort).append(" ")
      }
      sb.append("\n")
    }
    sb.toString()
  }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def endpointsJson() = {
    for (app <- schedulerService.listApps) yield {
      Map("id" -> app.id, "instances" -> taskTracker.get(app.id))
    }
  }
}