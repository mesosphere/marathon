package mesosphere.marathon.api.v2

import scala.language.postfixOps
import com.google.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import com.codahale.metrics.annotation.Timed
import javax.servlet.http.HttpServletRequest
import java.util.logging.Logger
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.event.exec.{ExecEventModule, ExecCallbackSubscriptionService}
import scala.concurrent.Await
import mesosphere.marathon.BadRequestException

// TODO(ian-kent) can be refactored with HttpEventSubscriptionsResource
@Path("v2/eventSubscriptionsExec")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class ExecEventSubscriptionsResource {
  // TODO(everpeace) this should be configurable option?
  val timeout = ExecEventModule.timeout.duration
  val log = Logger.getLogger(getClass.getName)

  @Inject(optional = true)
  val service: ExecCallbackSubscriptionService = null

  @GET
  @Timed
  def listSubscribers(@Context req: HttpServletRequest) = {
    validateSubscriptionService
    Await.result(service.getSubscribers, timeout)
  }

  @POST
  @Timed
  def subscribe(@Context req: HttpServletRequest, @QueryParam("execCmd") callbackUrl: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Subscribe(req.getRemoteAddr, callbackUrl))
    Await.result(future, timeout)
  }

  @DELETE
  @Timed
  def unsubscribe(@Context req: HttpServletRequest, @QueryParam("execCmd") callbackUrl: String) = {
    validateSubscriptionService
    val future = service.handleSubscriptionEvent(Unsubscribe(req.getRemoteAddr, callbackUrl))
    Await.result(future, timeout)
  }

  private def validateSubscriptionService = {
    if (Option(service).isEmpty) throw new BadRequestException(
      "exec event callback system is not running on this Marathon instance. " +
        "Please re-start this instance with \"--event_subscriber exec_callback\"."
    )
  }
}
