package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import com.google.inject.Inject

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.event.http.HttpCallbackSubscriptionService
import mesosphere.marathon.event.{ Subscribe, Unsubscribe }
import mesosphere.marathon.{ BadRequestException, MarathonConf }

@Path("v2/eventSubscriptions")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class EventSubscriptionsResource @Inject() (val config: MarathonConf) extends RestResource {

  @Inject(optional = true) val service: HttpCallbackSubscriptionService = null

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest): Response = {
    validateSubscriptionService()
    ok(result(service.getSubscribers))
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String): Response = {
    validateSubscriptionService()
    val future = service.handleSubscriptionEvent(Subscribe(req.getRemoteAddr, callbackUrl))
    ok(result(future))
  }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("callbackUrl") callbackUrl: String): Response = {
    validateSubscriptionService()
    val future = service.handleSubscriptionEvent(Unsubscribe(req.getRemoteAddr, callbackUrl))
    ok(result(future))
  }

  private def validateSubscriptionService(): Unit = {
    if (service eq null) throw new BadRequestException(
      "http event callback system is not running on this Marathon instance. " +
        "Please re-start this instance with \"--event_subscriber http_callback\"."
    )
  }
}
