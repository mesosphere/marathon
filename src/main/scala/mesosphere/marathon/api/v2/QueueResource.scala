package mesosphere.marathon
package api.v2

import java.time.Clock

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launchqueue.{LaunchQueue, LaunchStats}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, UpdateRunSpec, ViewRunSpec}
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId
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
    val config: MarathonConf,
    launchStats: LaunchStats
)(implicit val executionContext: ExecutionContext) extends AuthResource {

  import QueueResource._

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@Context req: HttpServletRequest, @QueryParam("embed") embed: java.util.Set[String]): Response = authenticated(req) { implicit identity =>
    val embedLastUnusedOffers = embed.contains(QueueResource.EmbedLastUnusedOffers)
    val allStats = result(launchStats.getStatistics())
    val stats = allStats.filter(t => isAuthorized(ViewRunSpec, t.runSpec))
    ok(Raml.toRaml((stats, embedLastUnusedOffers, clock)))
  }

  @DELETE
  @Path("""{runSpecId:.+}/delay""")
  def resetDelay(
    @PathParam("runSpecId") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val runSpecId = id.toRootPath
    val runSpecScheduled = result(instanceTracker.specInstances(runSpecId)).exists(_.isScheduled)
    val maybeRunSpec = if (runSpecScheduled) groupManager.runSpec(runSpecId) else None
    withAuthorization(UpdateRunSpec, maybeRunSpec, notFound(runSpecNotFoundTasksQueue(runSpecId))) { runSpec =>
      launchQueue.resetDelay(runSpec)
      noContent
    }
  }
}

object QueueResource {
  val EmbedLastUnusedOffers = "lastUnusedOffers"

  private def runSpecNotFoundTasksQueue: PathId => String =
    (id: PathId) => s"Application $id not found in tasks queue."
}
