package mesosphere.marathon.api.v2

import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import javax.inject.{Named, Inject}
import mesosphere.marathon.event.EventModule
import com.google.common.eventbus.EventBus
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

@Path("v2/groups")
@Consumes(Array(MediaType.APPLICATION_JSON))
class GroupsResource @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager) {

  @POST
  @Path("{id}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def deploy(
    @Context req: HttpServletRequest,
    @PathParam("id") id: String,
    @Valid group: Group
  ) = {
    group
  }
}
