package mesosphere.marathon.api.v2

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.plugin.auth.{ Authorizer, Authenticator, ViewAppOrGroup }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import org.slf4j.LoggerFactory

@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService,
                          val authenticator: Authenticator,
                          val authorizer: Authorizer,
                          val config: MarathonConf) extends AuthResource {

  val log = LoggerFactory.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String,
            @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthorized(req, resp, ViewAppOrGroup, appId.toRootPath) { implicit principal =>
      val id = appId.toRootPath
      val versions = service.listAppVersions(id).toSeq
      if (versions.isEmpty) unknownApp(id)
      else ok(jsonObjString("versions" -> versions))
    }
  }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String,
           @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthorized(req, resp, ViewAppOrGroup, appId.toRootPath) { implicit principal =>
      val id = appId.toRootPath
      val timestamp = Timestamp(version)
      service.getApp(id, timestamp).map(app => ok(jsonString(V2AppDefinition(app))))
        .getOrElse(unknownApp(id, Option(timestamp)))
    }
  }
}
