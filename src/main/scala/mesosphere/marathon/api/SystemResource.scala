package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.google.inject.Inject
import com.typesafe.config.{ Config, ConfigRenderOptions }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.AuthorizedResource.{ SystemConfig, SystemMetrics }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, ViewResource }
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.raml.MetricsConversion._

/**
  * System Resource gives access to system level functionality.
  * All system resources can be protected via ACLs.
  */
@Path("")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class SystemResource @Inject() (val config: MarathonConf, cfg: Config)(implicit
  val authenticator: Authenticator,
    val authorizer: Authorizer) extends RestResource with AuthResource {

  @GET
  @Path("ping")
  def ping(): Response = ok("pong")

  @GET
  @Path("metrics")
  def metrics(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemMetrics){
      ok(jsonString(Raml.toRaml(Metrics.snapshot())))
    }
  }

  @GET
  @Path("config")
  def config(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      ok(cfg.root().render(ConfigRenderOptions.defaults().setJson(true)))
    }
  }
}
