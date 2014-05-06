package mesosphere.marathon.api.v1

import com.codahale.metrics.annotation.Timed
import com.google.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named
import javax.ws.rs.{Produces, GET, Path}
import javax.ws.rs.core.{MediaType, Response}
import mesosphere.marathon.{ModuleNames, MarathonSchedulerService}
import scala.Array

//TODO(*): Think about an annotation allowing status vars to be exported.
@Path("v1/debug")
@Produces(Array(MediaType.APPLICATION_JSON))
class DebugResource @Inject()
    (schedulerService: MarathonSchedulerService,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean) {

  @GET
  @Path("isLeader")
  @Timed
  def isLeader(): Response = Response.ok().entity(leader.get()).build

  @GET
  @Path("leaderUrl")
  @Timed
  def leaderUrl(): Response =
    Response.ok()
      .entity(schedulerService.getLeader.getOrElse("not in HA mode.")).build
}
