package mesosphere.marathon
package core.event.impl.callback

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.pattern.ask
import akka.stream.Materializer
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.event.impl.callback.HttpEventActor._
import mesosphere.marathon.core.event.impl.callback.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.metrics.{ Metrics, MinMaxCounter, ServiceMetric, Timer }
import mesosphere.marathon.util.Retry
import mesosphere.util.CallerThreadExecutionContext
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
  * This actor subscribes to the event bus and distributes every event to all http callback listeners.
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

  class HttpEventActorMetrics {
    private val pre = ServiceMetric
    private val clazz = classOf[HttpEventActor]
    // the number of requests that are open without response
    val outstandingCallbacks: MinMaxCounter = Metrics.minMaxCounter(pre, clazz, "outstanding-callbacks")
    // the number of events that are broadcast
    val eventMeter: MinMaxCounter = Metrics.minMaxCounter(pre, clazz, "events")
    // the number of events that are not send to callback listeners due to backoff
    val skippedCallbacks: MinMaxCounter = Metrics.minMaxCounter(pre, clazz, "skipped-callbacks")
    // the number of callbacks that have failed during delivery
    val failedCallbacks: MinMaxCounter = Metrics.minMaxCounter(pre, clazz, "failed-callbacks")
    // the response time of the callback listeners
    val callbackResponseTime: Timer = Metrics.timer(pre, clazz, "callback-response-time")
  }
}

class HttpEventActor(
  conf: EventConf,
  subscribersKeeper: ActorRef,
  metrics: HttpEventActorMetrics,
  clock: Clock
)(implicit val materializer: Materializer)
    extends Actor with PlayJsonSupport {

  private[this] val log = LoggerFactory.getLogger(getClass)
  implicit val timeout = conf.eventRequestTimeout
  var limiter = Map.empty[String, EventNotificationLimit].withDefaultValue(NoLimit)
  private[this] val defaultSettings = ConnectionPoolSettings(context.system)

  def receive: Receive = {
    case event: MarathonEvent => resolveSubscribersForEventAndBroadcast(event)
    case Broadcast(event, subscribers) => broadcast(event, subscribers)
    case NotificationSuccess(url) => limiter += url -> NoLimit
    case NotificationFailed(url) => limiter += url -> limiter(url).nextFailed
    case _ => log.warn("Message not understood!")
  }

  def resolveSubscribersForEventAndBroadcast(event: MarathonEvent): Unit = {
    metrics.eventMeter.increment()
    log.info("POSTing to all endpoints.")
    val me = self
    import context.dispatcher
    // retry 3 times -> with 3 times the ask timeout
    val subscribers = Retry("Get Subscribers", maxAttempts = 3, maxDuration = timeout.duration * 3) {
      (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
    }(context.system.scheduler, context.dispatcher)
    subscribers.map { subscribers =>
      me ! Broadcast(event, subscribers)
    }.onFailure {
      case NonFatal(e) =>
        log.error(s"While trying to resolve subscribers for event $event", e)
    }
  }

  def broadcast(event: MarathonEvent, subscribers: EventSubscribers): Unit = {
    val (active, limited) = subscribers.urls.partition(limiter(_).notLimited)
    if (limited.nonEmpty) {
      log.info(s"""Will not send event ${event.eventType} to unresponsive hosts: ${limited.mkString(" ")}""")
    }
    //remove all unsubscribed callback listener
    limiter = limiter.filterKeys(subscribers.urls).iterator.toMap.withDefaultValue(NoLimit)
    metrics.skippedCallbacks.increment(limited.size.toLong)
    val jsonEvent = eventToJson(event)
    active.foreach(url => Try(post(url, jsonEvent, self)) match {
      case Success(res) =>
      case Failure(ex) =>
        log.warn(s"Failed to post $event to $url because ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        metrics.failedCallbacks.increment()
        self ! NotificationFailed(url)
    })
  }

  def post(url: String, event: JsValue, eventActor: ActorRef): Unit = {
    log.info("Sending POST to:" + url)

    metrics.outstandingCallbacks.increment()
    val start = clock.now()

    val response = request(RequestBuilding.Post(url, event)(playJsonMarshaller[JsValue], context.dispatcher))
    response.onComplete { _ =>
      metrics.outstandingCallbacks.decrement()
      metrics.callbackResponseTime.update(start.until(clock.now()))
    }(CallerThreadExecutionContext.callerThreadExecutionContext)

    response.onComplete {
      case Success(res) if res.status.isSuccess =>
        val inTime = start.until(clock.now()) < conf.slowConsumerDuration
        eventActor ! (if (inTime) NotificationSuccess(url) else NotificationFailed(url))
      case Success(res) =>
        log.warn(s"No success response for post $event to $url")
        metrics.failedCallbacks.increment()
        eventActor ! NotificationFailed(url)
      case Failure(ex) =>
        log.warn(s"Failed to post $event to $url because ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        metrics.failedCallbacks.increment()
        eventActor ! NotificationFailed(url)
    }(context.dispatcher)
  }

  private[impl] def request(httpRequest: HttpRequest): Future[HttpResponse] = {
    implicit val actorSystem = context.system
    val connectionSetting = defaultSettings.connectionSettings.withConnectingTimeout(timeout.duration)
    Http().singleRequest(
      request = httpRequest,
      settings = defaultSettings.withConnectionSettings(connectionSetting)
    ).map{ response =>
        response.discardEntityBytes() // forget about the body
        response
      }(CallerThreadExecutionContext.callerThreadExecutionContext)
  }
}

