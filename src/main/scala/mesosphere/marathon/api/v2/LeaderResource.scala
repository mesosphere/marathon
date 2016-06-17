package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{ Context, Response }
import javax.ws.rs.{ GET, DELETE, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.{ MarathonSchedulerService, MarathonConf }

@Path("v2/leader")
class LeaderResource @Inject() (
  schedulerService: MarathonSchedulerService,
  electionService: ElectionService,
  val config: MarathonConf with HttpConf,
  val authenticator: Authenticator,
  val authorizer: Authorizer)
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
  def delete(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(UpdateResource, AuthorizedResource.Leader) {
      electionService.isLeader match {
        case false => notFound("There is no leader")
        case true =>
          electionService.abdicateLeadership()
          ok(jsonObjString("message" -> "Leadership abdicated"))
      }
    }
  }
}
