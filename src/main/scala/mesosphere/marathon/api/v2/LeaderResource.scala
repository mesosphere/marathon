package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ GET, DELETE, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.{ MarathonSchedulerService, MarathonConf }

@Path("v2/leader")
class LeaderResource @Inject() (
    schedulerService: MarathonSchedulerService,
    val config: MarathonConf with HttpConf)
    extends RestResource {

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(): Response = {
    schedulerService.getLeader match {
      case None => notFound(s"There is no leader")
      case Some(leader) => 
        ok(Map("leader" -> leader))
    }
  }

  @DELETE
  @Produces(Array(MediaType.APPLICATION_JSON))
  def delete(): Response = {
    schedulerService.isLeader match {
      case false => notFound(s"Marathon instance '${config.hostname}' is not a leader")
      case true => 
        schedulerService.abdicateLeadership()
        ok(Map("message" -> "Leadership abdicted"))
    }
  }
}
