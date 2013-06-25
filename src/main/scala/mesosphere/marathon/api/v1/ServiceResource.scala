package mesosphere.marathon.api.v1

import javax.ws.rs.{POST, Produces, Path}
import mesosphere.marathon.{MarathonSchedulerManager}
import javax.ws.rs.core.{Response, MediaType}
import com.yammer.metrics.annotation.Timed
import javax.inject.Inject
import javax.ws.rs.core.Response.Status
import javax.validation.Valid

/**
 * @author Tobi Knaup
 */
@Path("/v1/service")
@Produces(Array(MediaType.APPLICATION_JSON))
class ServiceResource @Inject() (manager: MarathonSchedulerManager) {

  @POST
  @Path("start")
  @Timed
  def start(@Valid service: ServiceDefinition): Response = {
    manager.startService(service)
    Response.noContent.build
  }

  @POST
  @Path("stop")
  @Timed
  def stop(service: ServiceDefinition): Response = {
    manager.stopService(service)
    Response.noContent.build
  }

  @POST
  @Path("scale")
  @Timed
  def scale(service: ServiceDefinition): Response = {
    manager.scaleService(service)
    Response.noContent.build
  }

}