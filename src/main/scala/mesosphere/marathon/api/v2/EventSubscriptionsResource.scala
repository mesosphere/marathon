package mesosphere.marathon.api.v2

import scala.language.postfixOps
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import com.codahale.metrics.annotation.Timed
import javax.servlet.http.HttpServletRequest
import java.util.logging.Logger
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.event.http.HttpCallbackSubscriptionService
import scala.concurrent.Await
import scala.concurrent.duration._
import mesosphere.marathon.BadRequestException

@Path("v2/event_subscriptions")
@Produces(Array(MediaType.APPLICATION_JSON))
class EventSubscriptionsResource @Inject()(service: HttpCallbackSubscriptionService) {
  // TODO(everpeace) this should be configurable option?
  val timeout = 5 seconds
  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest) = {
    validateSubscriptionService
    Await.result(service.getSubscribers, timeout)
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("callback_url") callback_url: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Subscribe(req.getRemoteAddr, callback_url))
    Await.result(future, timeout)
  }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("callback_url") callback_url: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Unsubscribe(req.getRemoteAddr, callback_url))
    Await.result(future, timeout)
  }

  private def validateSubscriptionService = {
    if (Option(service).isEmpty) throw new BadRequestException(
      "http event callback system is not running on Marathon. Please re-start Marathon with \"--event_subscriber http_callback\"."
    )
  }
}
