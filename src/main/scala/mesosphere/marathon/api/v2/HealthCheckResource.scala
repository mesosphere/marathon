package mesosphere.marathon.api.v2

import scala.language.postfixOps
import javax.ws.rs._
import javax.ws.rs.core.MediaType
import com.codahale.metrics.annotation.Timed
import java.util.logging.Logger

@Path("v2/health")
@Produces(Array(MediaType.APPLICATION_JSON))
class HealthCheckResource {
  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def check(@QueryParam("appId") appId: String) = {
  }
}
