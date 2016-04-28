package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ GET, DELETE, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.{ MarathonSchedulerService, MarathonConf }

@Path("v2/leader")
class LeaderResource @Inject() (
  schedulerService: MarathonSchedulerService,
  electionService: ElectionService,
  val config: MarathonConf with HttpConf)
    extends RestResource {

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    electionService.leaderHostPort match {
      case None => notFound("There is no leader")
      case Some(leader) =>
        ok(jsonObjString("leader" -> leader))
    }
  }

  @DELETE
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def delete(): Response = {
    electionService.isLeader match {
      case false => notFound("There is no leader")
      case true =>
        electionService.abdicateLeadership()
        ok(jsonObjString("message" -> "Leadership abdicated"))
    }
  }
}
