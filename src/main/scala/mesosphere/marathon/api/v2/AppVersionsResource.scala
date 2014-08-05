package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.MediaType

import com.codahale.metrics.annotation.Timed
import org.apache.log4j.Logger

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService, val config: MarathonConf) extends RestResource {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String) = {
    val id = appId.toRootPath
    val versions = service.listAppVersions(id).toSeq
    if (versions.isEmpty) unknownApp(id)
    else ok(Map("versions" -> versions))
  }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String) = {
    val id = appId.toRootPath
    service.getApp(id, Timestamp(version)) getOrElse unknownApp(id)
  }
}
