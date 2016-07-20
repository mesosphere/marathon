package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import com.google.inject.Inject
import com.wix.accord.dsl._
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.Validation.urlIsValid
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.event.{ HttpCallbackSubscriptionService, MarathonEvent, Subscribe, Unsubscribe }
import mesosphere.marathon.plugin.auth._

import scala.concurrent.Future

@Path("v2/eventSubscriptions")
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class EventSubscriptionsResource @Inject() (
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val service: HttpCallbackSubscriptionService) extends AuthResource {

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, AuthorizedResource.Events) {
      ok(jsonString(result(service.getSubscribers)))
    }
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String): Response =
    authenticated(req) { implicit identity =>
      withAuthorization(ViewResource, AuthorizedResource.Events) {
        withValid(callbackUrl) { callback =>
          val future: Future[MarathonEvent] = service.handleSubscriptionEvent(
            Subscribe(req.getRemoteAddr, callback))
          ok(jsonString(eventToJson(result(future))))
        }(EventSubscriptionsResource.httpCallbackValidator)
      }
    }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String): Response =
    authenticated(req) { implicit identity =>
      withAuthorization(ViewResource, AuthorizedResource.Events) {
        val future = service.handleSubscriptionEvent(Unsubscribe(req.getRemoteAddr, callbackUrl))
        ok(jsonString(eventToJson(result(future))))
      }
    }
}

object EventSubscriptionsResource {
  implicit val httpCallbackValidator = validator[String] { callback =>
    callback is urlIsValid
  }
}
