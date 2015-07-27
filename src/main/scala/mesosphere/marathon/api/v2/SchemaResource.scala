package mesosphere.marathon.api.v2

import javax.inject.{ Inject }
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType }
import mesosphere.marathon.MarathonConf
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import java.io.InputStream

@Path("v2/schemas")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class SchemaResource @Inject() (
    val config: MarathonConf) extends RestResource {

  @GET
  @Timed
  @Path("/app")
  def index(): InputStream = {
    getClass().getResourceAsStream("AppDefinition.json")
  }
}
