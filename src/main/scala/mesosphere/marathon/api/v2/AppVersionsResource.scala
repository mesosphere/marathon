package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.state.Timestamp
import java.util.logging.Logger
import mesosphere.marathon.api.Responses


@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String) = {
    val versions = service.listAppVersions(appId).toSeq
    if (versions.isEmpty) Responses.unknownApp(appId)
    else Response.ok(Map("versions" -> versions)).build
  }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String) = {

    val timestampVersion = Timestamp(version)
    val versions = service.listAppVersions(appId).toSeq
    
    service.getApp(appId, Timestamp(version)) match {
      case Some(appdef) => appdef
      case None => Responses.unknownApp(appId)
    }
  }

}
