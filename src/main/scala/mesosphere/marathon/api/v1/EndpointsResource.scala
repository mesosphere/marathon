package mesosphere.marathon.api.v1

import javax.ws.rs.{PathParam, GET, Produces, Path}
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.state.MarathonStore
import scala.concurrent.Await
import javax.ws.rs.core.Response.Status

/**
 * @author Tobi Knaup
 */

@Path("v1/endpoints")
class EndpointsResource @Inject()(
    schedulerService: MarathonSchedulerService,
    taskTracker: TaskTracker,
    store: MarathonStore[AppDefinition]) {

  import scala.concurrent.ExecutionContext.Implicits.global


  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def endpoints() = {
    val sb = new StringBuilder
    for (app <- schedulerService.listApps()) {
      val tasks = taskTracker.get(app.id)

      for ((port, i) <- app.ports.zipWithIndex) {
        val cleanId = app.id.replaceAll("\\s+", "_")
        sb.append(s"${cleanId}_$port $port ")

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
  @Timed
  def endpointsJson() = {
    for (app <- schedulerService.listApps) yield {
      val instances = taskTracker.get(app.id).map(t => {
        Map("host" -> t.getHost, "ports" -> t.getPortsList)
      })
      Map("id" -> app.id, "ports" -> app.ports, "instances" -> instances)
    }
  }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  @Timed
  def endpointsForApp(@PathParam("id") id: String): Response = {
    val f = store.fetch(id).map(_ match {
      case Some(app) => {
        val instances = taskTracker.get(id).map(t => {
          Map("host" -> t.getHost, "ports" -> t.getPortsList)
        })
        val body = Map(
          "id" -> app.id,
          "ports" -> app.ports,
          "instances" -> instances)
        Response.ok(body).build
      }
      case None => Response.status(Status.NOT_FOUND).build
    })
    Await.result(f, store.defaultWait)
  }
}