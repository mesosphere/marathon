package mesosphere.marathon.api

import java.io.StringWriter
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Request, Response, Variant }

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
class SystemResource @Inject() (metrics: MetricRegistry, val config: MarathonConf)(implicit
  val authenticator: Authenticator,
    val authorizer: Authorizer) extends RestResource with AuthResource {

  private[this] val TEXT_WILDCARD_TYPE = MediaType.valueOf("text/*")

  private[this] lazy val mapper = new ObjectMapper().registerModule(
    new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, false, MetricFilter.ALL)
  )

  /**
    * ping sends a pong to a client.
    *
    * ping doesn't use the `Produces` or `Consumes` tags because those do specific checking for a client
    * Accept header that may or may not exist. In the interest of compatibility we dynamically generate
    * a "pong" content object depending on the client's preferred Content-Type, or else assume `text/plain`
    * if the client specifies an Accept header compatible with `text/{wildcard}`. Otherwise no entity is
    * returned and status is set to "no content" (HTTP 204).
    */
  @GET
  @Path("ping")
  @Timed
  def ping(@Context req: Request): Response = {
    import MediaType._

    val v = Variant.mediaTypes(
      TEXT_PLAIN_TYPE, // first, in case client accepts */* or text/*
      TEXT_HTML_TYPE,
      APPLICATION_JSON_TYPE,
      TEXT_WILDCARD_TYPE
    ).add.build

    Option[Variant](req.selectVariant(v)).map(variant => variant -> variant.getMediaType).collect {
      case (variant, mediaType) if mediaType.isCompatible(APPLICATION_JSON_TYPE) =>
        // return a properly formatted JSON object
        "\"pong\"" -> APPLICATION_JSON_TYPE
      case (variant, mediaType) if mediaType.isCompatible(TEXT_WILDCARD_TYPE) =>
        // otherwise we send back plain text
        "pong" -> {
          if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            TEXT_PLAIN_TYPE // never return a Content-Type w/ a wildcard
          } else {
            mediaType
          }
        }
    }.map { case (obj, mediaType) => Response.ok(obj).`type`(mediaType.toString).build }.getOrElse {
      Response.noContent().build
    }
  }

  @GET
  @Path("metrics")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
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
