package mesosphere.marathon.event.http

import javax.inject.Inject

import akka.actor._
import akka.pattern.ask
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.HttpEventActor._
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import spray.client.pipelining.{ sendReceive, _ }
import spray.http.{ HttpRequest, HttpResponse }
import spray.httpx.PlayJsonSupport

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal
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

  private case class Broadcast(event: MarathonEvent, subscribers: EventSubscribers)

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

class HttpEventActor(conf: HttpEventConfiguration,
                     subscribersKeeper: ActorRef,
                     metrics: HttpEventActorMetrics,
                     clock: Clock)
    extends Actor with ActorLogging with PlayJsonSupport {

  implicit val timeout = HttpEventModule.timeout
  def pipeline(implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = {
    addHeader("Accept", "application/json") ~> sendReceive
  }
  var limiter = Map.empty[String, EventNotificationLimit].withDefaultValue(NoLimit)

  def receive: Receive = {
    case event: MarathonEvent          => resolveSubscribersForEventAndBroadcast(event)
    case Broadcast(event, subscribers) => broadcast(event, subscribers)
    case NotificationSuccess(url)      => limiter += url -> NoLimit
    case NotificationFailed(url)       => limiter += url -> limiter(url).nextFailed
    case _                             => log.warning("Message not understood!")
  }

  def resolveSubscribersForEventAndBroadcast(event: MarathonEvent): Unit = {
    metrics.eventMeter.mark()
    log.info("POSTing to all endpoints.")
    val me = self
    import context.dispatcher
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers].map { subscribers =>
      me ! Broadcast(event, subscribers)
    }.onFailure {
      case NonFatal(e) => log.error("While trying to resolve subscribers for event {}", event)
    }
  }

  def broadcast(event: MarathonEvent, subscribers: EventSubscribers): Unit = {
    val (active, limited) = subscribers.urls.partition(limiter(_).notLimited)
    if (limited.nonEmpty) {
      log.info(s"""Will not send event ${event.eventType} to unresponsive hosts: ${limited.mkString(" ")}""")
    }
    //remove all unsubscribed callback listener
    limiter = limiter.filterKeys(subscribers.urls).iterator.toMap.withDefaultValue(NoLimit)
    metrics.skippedCallbacks.mark(limited.size)
    active.foreach(post(_, event, self))
  }

  def post(url: String, event: MarathonEvent, eventActor: ActorRef): Unit = {
    log.info("Sending POST to:" + url)

    metrics.outstandingCallbacks.inc()
    val start = clock.now()
    val request = Post(url, eventToJson(event))

    val response = pipeline(context.dispatcher)(request)

    import context.dispatcher
    response.onComplete {
      case _ =>
        metrics.outstandingCallbacks.dec()
        metrics.callbackResponseTime.update(start.until(clock.now()))
    }
    response.onComplete {
      case Success(res) if res.status.isSuccess =>
        val inTime = start.until(clock.now()) < conf.slowConsumerTimeout
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

