package mesosphere.marathon.api.v2

import scala.language.postfixOps
import com.google.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import com.codahale.metrics.annotation.Timed
import javax.servlet.http.HttpServletRequest
import org.apache.log4j.Logger
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.event.http.{HttpEventModule, HttpCallbackSubscriptionService}
import scala.concurrent.Await
import mesosphere.marathon.BadRequestException

@Path("v2/eventSubscriptions")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class EventSubscriptionsResource {
  // TODO(everpeace) this should be configurable option?
  val timeout = HttpEventModule.timeout.duration
  val log = Logger.getLogger(getClass.getName)

  @Inject(optional = true)
  val service: HttpCallbackSubscriptionService = null

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest) = {
    validateSubscriptionService
    Await.result(service.getSubscribers, timeout)
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Subscribe(req.getRemoteAddr, callbackUrl))
    Await.result(future, timeout)
  }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Unsubscribe(req.getRemoteAddr, callbackUrl))
    Await.result(future, timeout)
  }

  private def validateSubscriptionService = {
    if (service eq null) throw new BadRequestException(
      "http event callback system is not running on this Marathon instance. " +
        "Please re-start this instance with \"--event_subscriber http_callback\"."
    )
  }
}
