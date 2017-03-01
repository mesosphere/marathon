package mesosphere.marathon
package api

import java.io.StringWriter
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.{ MetricFilter, MetricRegistry }
import com.codahale.metrics.annotation.Timed
import com.codahale.metrics.json.MetricsModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.io.IO
import mesosphere.marathon.plugin.auth.AuthorizedResource.SystemConfig
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, ViewResource }

/**
  * System Resource gives access to system level functionality.
  * All system resources can be protected via ACLs.
  */
@Path("")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class SystemResource @Inject() (metrics: MetricRegistry, val config: MarathonConf)(implicit
  val authenticator: Authenticator,
    val authorizer: Authorizer) extends RestResource with AuthResource {

  private[this] lazy val mapper = new ObjectMapper().registerModule(
    new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, false, MetricFilter.ALL)
  )

  @GET
  @Path("ping")
  @Timed
  def ping(): Response = {
    ok("pong")
  }

  @GET
  @Path("metrics")
  @Timed
  def metrics(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig){
      IO.using(new StringWriter()) { writer =>
        mapper.writer().writeValue(writer, metrics)
        ok(writer.toString)
      }
    }
  }
}
