package mesosphere.marathon.api.v2

import javax.inject.{Named, Inject}
import mesosphere.marathon.event.{Unsubscribe, Subscribe, EventModule}
import com.google.common.eventbus.EventBus
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import com.codahale.metrics.annotation.Timed
import javax.servlet.http.HttpServletRequest
import java.util.logging.Logger
import mesosphere.marathon.event.Subscribe
import mesosphere.marathon.event.Unsubscribe
import mesosphere.marathon.BadRequestException

@Path("v2/event_subscriptions")
@Produces(Array(MediaType.APPLICATION_JSON))
class EventSubscriptionsResource @Inject()(@Named(EventModule.busName) eventBus: Option[EventBus]) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest) = {
    throw new BadRequestException(
      "This endpoint is not supported. See /help for details."
    )
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("callback_url") callback_url: String) = {
    if (eventBus.nonEmpty) {
      val ip = req.getRemoteAddr
      eventBus.get.post(Subscribe(ip, callback_url))
    }else{
      log.warning("no event bus.  ${callback_url} wasn't registered.")
    }
  }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("callback_url") callback_url: String) = {
    if (eventBus.nonEmpty) {
      val ip = req.getRemoteAddr
      eventBus.get.post(Unsubscribe(ip, callback_url))
    }else{
      log.warning("no event bus.  ${callback_url} wasn't unregistered.")
    }
  }

}
