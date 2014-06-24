package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{ MediaType, Response }
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.log4j.Logger
import mesosphere.marathon.api.Responses
import PathId._

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String) = {
    val id = appId.toRootPath
    val versions = service.listAppVersions(id).toSeq
    if (versions.isEmpty) Responses.unknownApp(id)
    else Response.ok(Map("versions" -> versions)).build
  }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String) = {
    val id = appId.toRootPath
    service.getApp(id, Timestamp(version)) getOrElse Responses.unknownApp(id)
  }
}
