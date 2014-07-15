package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import mesosphere.marathon.BuildInfo

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource {
  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(): Response = {
    Response.ok(
      Map(
        "name" -> BuildInfo.name,
        "version" -> BuildInfo.version
      )
    ).build()
  }
}
