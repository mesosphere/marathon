package mesosphere.marathon.event.http

import akka.actor.{ Actor, ActorLogging }
import akka.pattern.pipe
import mesosphere.marathon.event.http.SubscribersKeeperActor._
import mesosphere.marathon.event.{ MarathonSubscriptionEvent, Subscribe, Unsubscribe }
import mesosphere.marathon.state.EntityStore

import scala.concurrent.Future

class SubscribersKeeperActor(val store: EntityStore[EventSubscribers]) extends Actor with ActorLogging {

  implicit val ec = HttpEventModule.executionContext

  override def receive: Receive = {

    case event @ Subscribe(_, callbackUrl, _, _) =>
      val addResult: Future[EventSubscribers] = add(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        addResult.map { subscribers =>
          if (subscribers.urls.contains(callbackUrl))
            log.info("Callback [%s] subscribed." format callbackUrl)
          event
        }

      subscription pipeTo sender()

    case event @ Unsubscribe(_, callbackUrl, _, _) =>
      val removeResult: Future[EventSubscribers] = remove(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        removeResult.map { subscribers =>
          if (!subscribers.urls.contains(callbackUrl))
            log.info("Callback [%s] unsubscribed." format callbackUrl)
          event
        }

      subscription pipeTo sender()

    case GetSubscribers =>
      val subscription = store.fetch(Subscribers).map(_.getOrElse(EventSubscribers()))

      subscription pipeTo sender()
  }

  protected[this] def add(callbackUrl: String): Future[EventSubscribers] =
    store.modify(Subscribers) { deserialize =>
      val existingSubscribers = deserialize()
      if (existingSubscribers.urls.contains(callbackUrl)) {
        log.info("Existing callback [%s] resubscribed." format callbackUrl)
        existingSubscribers
      }
      else EventSubscribers(existingSubscribers.urls + callbackUrl)
    }

  protected[this] def remove(callbackUrl: String): Future[EventSubscribers] =
    store.modify(Subscribers) { deserialize =>
      val existingSubscribers = deserialize()

      if (existingSubscribers.urls.contains(callbackUrl))
        EventSubscribers(existingSubscribers.urls - callbackUrl)

      else {
        log.warning("Attempted to unsubscribe nonexistent callback [%s]." format callbackUrl)
        existingSubscribers
      }
    }
}

object SubscribersKeeperActor {

  case object GetSubscribers

  final val Subscribers = "http_event_subscribers"
}
