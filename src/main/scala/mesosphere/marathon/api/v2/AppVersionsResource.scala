package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, ViewApp }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ GroupManager, Timestamp }
import mesosphere.marathon.{ UnknownAppException, MarathonConf, MarathonSchedulerService }
import org.slf4j.LoggerFactory

@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(service: MarathonSchedulerService,
                          groupManager: GroupManager,
                          val authenticator: Authenticator,
                          val authorizer: Authorizer,
                          val config: MarathonConf) extends AuthResource {

  val log = LoggerFactory.getLogger(getClass.getName)

  @GET
  @Timed
  def index(@PathParam("appId") appId: String,
            @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val id = appId.toRootPath
    withAuthorization(ViewApp, result(groupManager.app(id)), unknownApp(id)) { _ =>
      ok(jsonObjString("versions" -> service.listAppVersions(id).toSeq))
    }
  }

  @GET
  @Timed
  @Path("{version}")
  def show(@PathParam("appId") appId: String,
           @PathParam("version") version: String,
           @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val id = appId.toRootPath
    val timestamp = Timestamp(version)
    withAuthorization(ViewApp, service.getApp(id, timestamp), unknownApp(id, Some(timestamp))) { app =>
      ok(jsonString(app))
    }
  }
}
