package mesosphere.marathon
package api.v2

import java.time.Clock
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType}
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec}
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId._

import scala.async.Async.{await, async}
import scala.concurrent.ExecutionContext

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    clock: Clock,
    launchQueue: LaunchQueue,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val config: MarathonConf)(implicit val executionContext: ExecutionContext) extends AuthResource {

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(
    @Context req: HttpServletRequest,
    @QueryParam("embed") embed: java.util.Set[String],
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val embedLastUnusedOffers = embed.contains(QueueResource.EmbedLastUnusedOffers)
      val maybeStats = await(launchQueue.listWithStatistics)
      val stats = maybeStats.filter(t => t.inProgress && isAuthorized(ViewRunSpec, t.runSpec))
      ok(Raml.toRaml((stats, embedLastUnusedOffers, clock)))
    }
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(
    @PathParam("runSpecId") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val appId = id.toRootPath
      val maybeApps = await(launchQueue.list)
      val maybeApp = maybeApps.find(_.runSpec.id == appId).map(_.runSpec)
      withAuthorization(UpdateRunSpec, maybeApp, notFound(s"Application $appId not found in tasks queue.")) { app =>
        launchQueue.resetDelay(app)
        noContent
      }
    }
  }
}

object QueueResource {
  val EmbedLastUnusedOffers = "lastUnusedOffers"
}
