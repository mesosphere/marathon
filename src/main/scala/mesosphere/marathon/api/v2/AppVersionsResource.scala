package mesosphere.marathon
package api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType}
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, ViewRunSpec}
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppVersionsResource(
    service: MarathonSchedulerService,
    groupManager: GroupManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val config: MarathonConf
)(implicit val executionContext: ExecutionContext)
    extends AuthResource {

  @GET
  def index(@PathParam("appId") appId: String, @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val id = appId.toAbsolutePath
        withAuthorization(ViewRunSpec, groupManager.app(id), unknownApp(id)) { _ =>
          val versions = raml.VersionList(service.listAppVersions(id).map(_.toOffsetDateTime))
          ok(versions)
        }
      }
    }

  @GET
  @Path("{version}")
  def show(
      @PathParam("appId") appId: String,
      @PathParam("version") version: String,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val id = appId.toAbsolutePath
        val timestamp = Timestamp(version)
        withAuthorization(ViewRunSpec, service.getApp(id, timestamp), unknownApp(id, Some(timestamp))) { app =>
          ok(Raml.toRaml(app))
        }
      }
    }
}
