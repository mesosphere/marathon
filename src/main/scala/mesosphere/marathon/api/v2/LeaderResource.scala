package mesosphere.marathon
package api.v2

import akka.actor.Scheduler
import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{Context, MediaType}
import javax.ws.rs._
import com.google.inject.Inject
import mesosphere.marathon.HttpConf
import mesosphere.marathon.api.{AuthResource, RestResource}
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import mesosphere.marathon.raml.RuntimeConfiguration
import Validation._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import mesosphere.marathon.stream.UriIO

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object LeaderResource {
  val abdicationDelay = 500.millis
}

@Path("v2/leader")
class LeaderResource @Inject() (
    electionService: ElectionService,
    val config: MarathonConf with HttpConf,
    val runtimeConfigRepo: RuntimeConfigurationRepository,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val scheduler: Scheduler
)(implicit val executionContext: ExecutionContext)
    extends RestResource
    with AuthResource {

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        withAuthorization(ViewResource, AuthorizedResource.Leader) {
          electionService.leaderHostPort match {
            case None => notFound("There is no leader")
            case Some(leader) => ok(raml.LeaderInfo(leader))
          }
        }
      }
    }

  @DELETE
  @Produces(Array(MediaType.APPLICATION_JSON))
  def delete(
      @QueryParam("backup") backupNullable: String,
      @QueryParam("restore") restoreNullable: String,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        checkAuthorization(UpdateResource, AuthorizedResource.Leader)
        if (electionService.isLeader) {
          val backup = validateOrThrow(Option(backupNullable))(optional(UriIO.valid))
          val restore = validateOrThrow(Option(restoreNullable))(optional(UriIO.valid))
          await(runtimeConfigRepo.store(RuntimeConfiguration(backup, restore)))

          scheduler.scheduleOnce(LeaderResource.abdicationDelay) {
            electionService.abdicateLeadership()
          }

          ok(raml.Error(message = "Leadership abdicated"))
        } else {
          notFound("There is no leader")
        }
      }
    }
}
