package mesosphere.marathon.api.v1

import javax.ws.rs.{GET, Produces, Path}
import scala.Array
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import com.codahale.metrics.annotation.Timed

/**
 * @author Tobi Knaup
 */

@Path("v1/services")
@Produces(Array(MediaType.APPLICATION_JSON))
class ServicesResource @Inject() (service: MarathonSchedulerService) {

  @GET
  @Timed
  def index() = {
    service.listServices()
  }
}