package mesosphere.marathon
package api.v2

import akka.actor.Scheduler
import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{ Context, Response }
import javax.ws.rs._

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import mesosphere.marathon.raml.RuntimeConfiguration
import Validation._
import mesosphere.marathon.stream.UriIO
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
    val scheduler: Scheduler)(implicit executionContext: ExecutionContext)
  extends RestResource with AuthResource {

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, AuthorizedResource.Leader) {
      electionService.leaderHostPort match {
        case None => notFound("There is no leader")
        case Some(leader) =>
          ok(jsonObjString("leader" -> leader))
      }
    }
  }

  @DELETE
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def delete(
    @QueryParam("backup") backupNullable: String,
    @QueryParam("restore") restoreNullable: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(UpdateResource, AuthorizedResource.Leader) {
      if (electionService.isLeader) {
        assumeValid {
          val backup = validateOrThrow(Option(backupNullable))(optional(UriIO.valid))
          val restore = validateOrThrow(Option(restoreNullable))(optional(UriIO.valid))
          result(runtimeConfigRepo.store(RuntimeConfiguration(backup, restore)))

          scheduler.scheduleOnce(LeaderResource.abdicationDelay) { electionService.abdicateLeadership() }

          ok(jsonObjString("message" -> s"Leadership abdicated"))
        }
      } else {
        notFound("There is no leader")
      }
    }
  }
}
