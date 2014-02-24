package mesosphere.marathon.event.http

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import mesosphere.marathon.event.{
  MarathonSubscriptionEvent,
  Unsubscribe,
  Subscribe
}
import mesosphere.marathon.event.http.SubscribersKeeperActor._
import mesosphere.marathon.state.MarathonStore
import scala.concurrent.Future

class SubscribersKeeperActor(val store: MarathonStore[EventSubscribers]) extends Actor with ActorLogging {

  implicit val ec = HttpEventModule.executionContext

  override def receive = {

    case event @ Subscribe(_, callback_url, _) => {
      val addResult: Future[Option[EventSubscribers]] = add(callback_url)

      val subscribers: Future[MarathonSubscriptionEvent] =
        addResult.collect { case Some(subscribers) =>
          if (subscribers.urls.contains(callback_url))
            log.info("Callback [%s] subscribed." format callback_url)
          event
        }

      subscribers pipeTo sender
    }

    case event @ Unsubscribe(_, callback_url, _) => {
      val removeResult: Future[Option[EventSubscribers]] = remove(callback_url)

      val subscribers: Future[MarathonSubscriptionEvent] =
        removeResult.collect { case Some(subscribers) =>
          if (!subscribers.urls.contains(callback_url))
            log.info("Callback [%s] unsubscribed." format callback_url)
          event
        }

      subscribers pipeTo sender
    }

    case GetSubscribers => {
      val subscribers: Future[EventSubscribers] =
        store.fetch(SUBSCRIBERS).map {
          case Some(subscribers) => subscribers
          case _ => EventSubscribers()
        }

      subscribers pipeTo sender
    }

  }

  protected[this] def add(callback_url: String): Future[Option[EventSubscribers]] =
    store.modify(SUBSCRIBERS) { deserialize =>
      val existingSubscribers = deserialize()
      if (existingSubscribers.urls.contains(callback_url)) {
        log.info("Existing callback [%s] resubscribed." format callback_url)
        existingSubscribers
      }
      else EventSubscribers(existingSubscribers.urls + callback_url)
    }

  protected[this] def remove(callback_url: String): Future[Option[EventSubscribers]] =
    store.modify(SUBSCRIBERS) { deserialize =>
      val existingSubscribers = deserialize()

      if (existingSubscribers.urls.contains(callback_url))
        EventSubscribers(existingSubscribers.urls - callback_url)

      else {
        log.warning("Attempted to unsubscribe nonexistent callback [%s]." format callback_url)
        existingSubscribers
      }
    }
}

object SubscribersKeeperActor {

  case object GetSubscribers

  final val SUBSCRIBERS = "http_event_subscribers"
}