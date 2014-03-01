package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.state.Timestamp
import java.util.logging.Logger


@Produces(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String) =
    service.listAppVersions(appId) match {
      case Some(repo) if repo.history.nonEmpty => {
        Response.ok(Map("versions" -> repo.history.map(_.version))).build
      }
      case _ => Response.status(Response.Status.NOT_FOUND).build
    }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String) = {

    val timestampVersion = Timestamp(version)

    service.listAppVersions(appId).map { repo =>
      repo.history.find(app => app.version == timestampVersion)
    } match {
      case Some(version) => version
      case None => Response.status(Response.Status.NOT_FOUND).build
    }
  }

}
