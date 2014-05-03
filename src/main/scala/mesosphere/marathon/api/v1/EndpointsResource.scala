package mesosphere.marathon.api.v1

import javax.ws.rs.{PathParam, GET, Produces, Path}
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.Response.Status
import mesosphere.marathon.api.Responses
import scala.collection.JavaConverters._

/**
 * @author Tobi Knaup
 */

@Path("v1/endpoints")
class EndpointsResource @Inject()(
    schedulerService: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def endpoints() = appsToEndpointString(schedulerService.listApps().toSeq)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def endpointsJson() = {
    for (app <- schedulerService.listApps) yield {
      val instances = taskTracker.get(app.id)
      Map("id" -> app.id, "ports" -> app.ports, "instances" -> instances)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Path("{id}")
  @Timed
  def endpointsForApp(@PathParam("id") id: String): Response =
    schedulerService.getApp(id) match {
      case Some(app) => Response.ok(appsToEndpointString(Seq(app))).build
      case None => Response.status(Status.NOT_FOUND).entity(s"App '$id' does not exist").build
    }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  @Timed
  def endpointsForAppJson(@PathParam("id") id: String): Response =
    schedulerService.getApp(id) match {
      case Some(app) => {
        val instances = taskTracker.get(id)
        val body = Map(
          "id" -> app.id,
          "ports" -> app.ports,
          "instances" -> instances)
        Response.ok(body).build
      }
      case None => Responses.unknownApp(id)
    }

  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.
    */
  private def appsToEndpointString(apps: Seq[AppDefinition]): String = {
    val sb = new StringBuilder
    for (app <- apps) {
      val cleanId = app.id.replaceAll("\\s+", "_")
      val tasks = taskTracker.get(app.id)

      if (app.ports.isEmpty) {
        sb.append(s"${cleanId}   ")
        tasks.foreach { task =>
          sb.append(s"${task.getHost} ")
        }
        sb.append(s"\n")
      } else {
        for ((port, i) <- app.ports.zipWithIndex) {
          sb.append(s"${cleanId}_$port $port ")
          for (task <- tasks) {
            val ports = task.getPortsList.asScala.lift
            sb.append(s"${task.getHost}:${ports(i).getOrElse(0)} ")
          }
          sb.append("\n")
        }
      }
    }
    sb.toString()
  }

}
