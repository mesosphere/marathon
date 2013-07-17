package mesosphere.marathon.api.v1

import javax.ws.rs.{GET, Produces, Path}
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import mesosphere.marathon.{MarathonSchedulerService, AppRegistry}

/**
 * @author Tobi Knaup
 */

@Path("v1/endpoints")
@Produces(Array(MediaType.TEXT_PLAIN))
class EndpointsResource @Inject()(
    schedulerService: MarathonSchedulerService,
    appRegistry: AppRegistry) {

  @GET
  def endpoints() = {
    val sb = new StringBuilder
    for (app <- schedulerService.listApps()) {
      sb.append(app.id).append(" ").append(app.port).append(" ")

      for (instance <- appRegistry.appInstanceMap(app.id)) {
        val endpoint = instance.getServiceEndpoint
        sb.append(endpoint.getHost).append(":").append(endpoint.getPort).append(" ")
      }
      sb.append("\n")
    }
    sb.toString()
  }
}