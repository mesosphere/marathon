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
      val tasks = taskTracker.get(app.id)

      for ((port, i) <- app.ports.zipWithIndex) {
        sb.append(s"${app.id}_$port $port ")

        for (task <- tasks) {
          sb.append(s"${task.getHost}:${task.getPorts(i)} ")
        }
        sb.append("\n")
      }
    }
    sb.toString()
  }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def endpointsJson() = {
    for (app <- schedulerService.listApps) yield {
      val instances = taskTracker.get(app.id).map(t => {
        Map("host" -> t.getHost, "ports" -> t.getPortsList)
      })
      Map("id" -> app.id, "ports" -> app.ports, "instances" -> instances)
    }
  }
}