package mesosphere.marathon.api.v1

import javax.ws.rs.{GET, POST, Produces, Path}
import mesosphere.marathon.MarathonSchedulerService
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import javax.validation.Valid
import com.codahale.metrics.annotation.Timed

/**
 * @author Tobi Knaup
 */
@Path("v1/apps")
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject()(service: MarathonSchedulerService) {

  @GET
  @Timed
  def index() = {
    service.listServices()
  }

  @POST
  @Path("start")
  @Timed
  def start(@Valid app: AppDefinition): Response = {
    service.startApp(app)
    Response.noContent.build
  }

  @POST
  @Path("stop")
  @Timed
  def stop(app: AppDefinition): Response = {
    service.stopApp(app)
    Response.noContent.build
  }

  @POST
  @Path("scale")
  @Timed
  def scale(app: AppDefinition): Response = {
    service.scaleApp(app)
    Response.noContent.build
  }

}