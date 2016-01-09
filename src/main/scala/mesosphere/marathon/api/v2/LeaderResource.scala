package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ GET, DELETE, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ MarathonMediaType, LeaderInfo, RestResource }
import mesosphere.marathon.{ MarathonSchedulerService, MarathonConf }

@Path("v2/leader")
class LeaderResource @Inject() (
  leaderInfo: LeaderInfo,
  schedulerService: MarathonSchedulerService,
  val config: MarathonConf with HttpConf)
    extends RestResource {

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    leaderInfo.currentLeaderHostPort() match {
      case None => notFound("There is no leader")
      case Some(leader) =>
        ok(jsonObjString("leader" -> leader))
    }
  }

  @DELETE
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def delete(): Response = {
    leaderInfo.elected match {
      case false => notFound("There is no leader")
      case true =>
        schedulerService.abdicateLeadership()
        ok(jsonObjString("message" -> "Leadership abdicated"))
    }
  }
}
