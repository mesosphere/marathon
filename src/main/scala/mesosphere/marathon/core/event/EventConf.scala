package mesosphere.marathon.core.event

import java.util.concurrent.TimeUnit

import akka.util.Timeout
import org.rogach.scallop.ScallopConf
import mesosphere.marathon.api.v2.Validation.urlIsValid

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

trait EventConf extends ScallopConf {
  lazy val eventSubscriber = opt[String](
    "event_subscriber",
    descr = "The event subscription module to use. E.g. http_callback.",
    required = false,
    noshort = true)

  lazy val httpEventEndpoints = opt[String](
    "http_endpoints",
    descr = "The URLs of the event endpoints added to the current list of subscribers on startup. " +
      "You can manage this list during runtime by using the /v2/eventSubscriptions API endpoint.",
    required = false,
    validate = { parseHttpEventEndpoints(_).forall(urlIsValid(_).isSuccess) },
    noshort = true).map(parseHttpEventEndpoints)

  lazy val httpEventCallbackSlowConsumerTimeout = opt[Long](
    "http_event_callback_slow_consumer_timeout",
    descr = "A http event callback consumer is considered slow, if the delivery takes longer than this timeout (ms)",
    required = false,
    noshort = true,
    default = Some(10.seconds.toMillis)
  )

  lazy val httpEventRequestTimeout = opt[Long](
    "http_event_request_timeout",
    descr = "A http event request timeout (ms)",
    required = false,
    noshort = true,
    default = Some(10.seconds.toMillis)
  )

  //scalastyle:off magic.number
  lazy val eventStreamMaxOutstandingMessages = opt[Int](
    "event_stream_max_outstanding_messages",
    descr = "The event stream buffers events, that are not already consumed by clients. " +
      "This number defines the number of events that get buffered on the server side, before messages are dropped.",
    noshort = true,
    default = Some(50)
  )
  //scalastyle:on magic.number

  private[event] def httpCallbacksEnabled: Boolean = eventSubscriber.get.contains("http_callback")

  private[this] def parseHttpEventEndpoints(str: String): List[String] = str.split(',').map(_.trim).toList

  def slowConsumerDuration: FiniteDuration = httpEventCallbackSlowConsumerTimeout().millis

  def eventRequestTimeout: Timeout = Timeout(httpEventRequestTimeout(), TimeUnit.MILLISECONDS)

  def zkTimeoutDuration: FiniteDuration
}
