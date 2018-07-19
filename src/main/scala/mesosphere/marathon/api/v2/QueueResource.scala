package mesosphere.marathon
package api.v2

import java.time.Clock

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec}
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId._
import scala.concurrent.ExecutionContext

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    clock: Clock,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val config: MarathonConf)(implicit val executionContext: ExecutionContext) extends AuthResource {

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@Context req: HttpServletRequest, @QueryParam("embed") embed: java.util.Set[String]): Response = authenticated(req) { implicit identity =>
    val embedLastUnusedOffers = embed.contains(QueueResource.EmbedLastUnusedOffers)
    val maybeStats = result(launchQueue.listWithStatistics)
    val stats = maybeStats.filter(t => t.inProgress && isAuthorized(ViewRunSpec, t.runSpec))
    ok(Raml.toRaml((stats, embedLastUnusedOffers, clock)))
  }

  @DELETE
  @Path("""{appId:.+}/delay""")
  def resetDelay(
    @PathParam("appId") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    //    import scala.concurrent.ExecutionContext.Implicits.global
    val appId = id.toRootPath
    val appScheduled = result(instanceTracker.specInstances(appId)).exists(_.isScheduled)
    val maybeApp = if (appScheduled) groupManager.runSpec(appId) else None
    withAuthorization(UpdateRunSpec, maybeApp, notFound(s"Application $appId not found in tasks queue.")) { app =>
      launchQueue.resetDelay(app)
      noContent
    }
  }
}

object QueueResource {
  val EmbedLastUnusedOffers = "lastUnusedOffers"
}
