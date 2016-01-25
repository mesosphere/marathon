package mesosphere.marathon.event.http

import java.lang.System.currentTimeMillis
import javax.inject.Inject

import akka.actor._
import akka.pattern.ask
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.HttpEventActor._
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import spray.client.pipelining.{ sendReceive, _ }
import spray.http.{ HttpRequest, HttpResponse }
import spray.httpx.PlayJsonSupport

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

/**
  * This actor subscribes to the event bus and distributes every event to all http callback listener.
  * The list of active subscriptions is handled in the subscribersKeeper.
  * If a callback handler can not be reached or is slow, an exponential backoff is applied.
  */
object HttpEventActor {
  case class NotificationFailed(url: String)
  case class NotificationSuccess(url: String)

  case class EventNotificationLimit(failedCount: Long, backoffUntil: Option[Deadline]) {
    def nextFailed: EventNotificationLimit = {
      val next = failedCount + 1
      EventNotificationLimit(next, Some(math.pow(2, next.toDouble).seconds.fromNow))
    }
    def notLimited: Boolean = backoffUntil.fold(true)(_.isOverdue())
    def limited: Boolean = !notLimited
  }
  val NoLimit = EventNotificationLimit(0, None)

  class HttpEventActorMetrics @Inject() (metrics: Metrics) {
    private val pre = MetricPrefixes.SERVICE
    private val clazz = classOf[HttpEventActor]
    // the number of requests that are open without response
    val outstandingCallbacks = metrics.counter(metrics.name(pre, clazz, "outstanding-callbacks"))
    // the number of events that are broadcast
    val eventMeter = metrics.meter(metrics.name(pre, clazz, "events"))
    // the number of events that are not send to callback listeners due to backoff
    val skippedCallbacks = metrics.meter(metrics.name(pre, clazz, "skipped-callbacks"))
    // the number of callbacks that have failed during delivery
    val failedCallbacks = metrics.meter(metrics.name(pre, clazz, "failed-callbacks"))
    // the response time of the callback listeners
    val callbackResponseTime = metrics.timer(metrics.name(pre, clazz, "callback-response-time"))
  }
}

class HttpEventActor(conf: HttpEventConfiguration, val subscribersKeeper: ActorRef, metrics: HttpEventActorMetrics)
    extends Actor with ActorLogging with PlayJsonSupport {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout
  val pipeline: HttpRequest => Future[HttpResponse] = addHeader("Accept", "application/json") ~> sendReceive
  var limiter = Map.empty[String, EventNotificationLimit].withDefaultValue(NoLimit)

  def receive: Receive = {
    case event: MarathonEvent     => broadcast(event)
    case NotificationSuccess(url) => limiter += url -> NoLimit
    case NotificationFailed(url)  => limiter += url -> limiter(url).nextFailed
    case _                        => log.warning("Message not understood!")
  }

  def broadcast(event: MarathonEvent): Unit = {
    metrics.eventMeter.mark()
    log.info("POSTing to all endpoints.")
    val me = self
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers].foreach { subscribers =>
      val (active, limited) = subscribers.urls.partition(limiter(_).notLimited)
      if (limited.nonEmpty) {
        log.info(s"""Will not send event ${event.eventType} to unresponsive hosts: ${limited.mkString(" ")}""")
      }
      //remove all unsubscribed callback listener
      limiter = limiter.filterKeys(subscribers.urls).iterator.toMap.withDefaultValue(NoLimit)
      metrics.skippedCallbacks.mark(limited.size)
      active.foreach(post(_, event, me))
    }
  }

  def post(url: String, event: MarathonEvent, eventActor: ActorRef): Unit = {
    log.info("Sending POST to:" + url)

    metrics.outstandingCallbacks.inc()
    val request = Post(url, eventToJson(event))
    val response = pipeline(request)
    val start = currentTimeMillis()

    response.onComplete {
      case _ =>
        metrics.outstandingCallbacks.dec()
        metrics.callbackResponseTime.updateMillis(currentTimeMillis() - start)
    }
    response.onComplete {
      case Success(res) if res.status.isSuccess =>
        val inTime = (currentTimeMillis() - start) < conf.slowConsumerTimeout
        eventActor ! (if (inTime) NotificationSuccess(url) else NotificationFailed(url))
      case Success(res) =>
        log.warning(s"No success response for post $event to $url")
        metrics.failedCallbacks.mark()
        eventActor ! NotificationFailed(url)
      case Failure(ex) =>
        log.warning(s"Failed to post $event to $url because ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        metrics.failedCallbacks.mark()
        eventActor ! NotificationFailed(url)
    }
  }
}

