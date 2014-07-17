package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.marathon.{ MarathonSchedulerService, BuildInfo }

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (schedulerService: MarathonSchedulerService) {
  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(): Response = {
    Response.ok(
      Map(
        "name" -> BuildInfo.name,
        "version" -> BuildInfo.version,
        "leader" -> schedulerService.getLeader,
        "frameworkId" -> schedulerService.frameworkId.map(_.getValue)
      )
    ).build()
  }
}
