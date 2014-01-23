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

  import Implicits._
  import scala.concurrent.ExecutionContext.Implicits.global


  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def endpoints() = appsToEndpointString(schedulerService.listApps())

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def endpointsJson() = {
    for (app <- schedulerService.listApps) yield {
      val instances = taskTracker.get(app.id).map(t => t: Map[String, Object])
      Map("id" -> app.id, "ports" -> app.ports, "instances" -> instances)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Path("{id}")
  @Timed
  def endpointsForApp(@PathParam("id") id: String): Response = {
    val f = store.fetch(id).map(_ match {
      case Some(app) => Response.ok(appsToEndpointString(Seq(app))).build
      case None => Response.status(Status.NOT_FOUND).build
    })
    Await.result(f, store.defaultWait)
  }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  @Timed
  def endpointsForAppJson(@PathParam("id") id: String): Response = {
    val f = store.fetch(id).map(_ match {
      case Some(app) => {
        val instances = taskTracker.get(id).map(t => t: Map[String, Object])
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

  private def appsToEndpointString(apps: Seq[AppDefinition]): String = {
    val sb = new StringBuilder
    for (app <- apps) {
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

}