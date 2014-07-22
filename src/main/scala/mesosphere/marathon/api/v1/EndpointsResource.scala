package mesosphere.marathon.api.v1

import javax.inject.Inject
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ GET, Path, PathParam, Produces }
import scala.collection.JavaConverters._

import com.codahale.metrics.annotation.Timed

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Path("v1/endpoints")
class EndpointsResource @Inject() (
    schedulerService: MarathonSchedulerService,
    taskTracker: TaskTracker,
    val config: MarathonConf) extends RestResource {

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def endpoints() = appsToEndpointString(schedulerService.listApps().toSeq)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def endpointsJson() = {
    for (app <- schedulerService.listApps()) yield {
      val instances = taskTracker.get(app.id)
      Map("id" -> app.id, "ports" -> app.ports, "instances" -> instances)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Path("{id}")
  @Timed
  def endpointsForApp(@PathParam("id") id: String): Response = {
    schedulerService.getApp(id.toRootPath) match {
      case Some(app) => ok(appsToEndpointString(Seq(app)))
      case None      => unknownApp(id.toRootPath)
    }
  }

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Path("{id}")
  @Timed
  def endpointsForAppJson(@PathParam("id") id: String): Response = {
    val appId = id.toRootPath
    schedulerService.getApp(appId) match {
      case Some(app) => {
        val instances = taskTracker.get(appId)
        val body = Map(
          "id" -> app.id,
          "ports" -> app.ports,
          "instances" -> instances)
        ok(body)
      }
      case None => unknownApp(appId)
    }
  }

  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.
    */
  private def appsToEndpointString(apps: Seq[AppDefinition]): String = {
    val sb = new StringBuilder
    for (app <- apps) {
      val cleanId = app.id.toString.replaceAll("\\s+", "_")
      val tasks = taskTracker.get(app.id)

      if (app.ports.isEmpty) {
        sb.append(s"$cleanId   ")
        tasks.foreach { task =>
          sb.append(s"${task.getHost} ")
        }
        sb.append(s"\n")
      }
      else {
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
