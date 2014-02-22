package mesosphere.marathon.event.http

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.event.http.SubscribersKeeperActor._
import mesosphere.marathon.state.MarathonStore

class SubscribersKeeperActor(val store: MarathonStore[EventSubscribers]) extends Actor with ActorLogging {

  implicit val ec = HttpEventModule.executionContext

  override def receive = {
    case event@Subscribe(_, callback_url, _) =>
      add(callback_url).map {
        case Some(subscribers) =>
          if (subscribers.callback_urls.contains(callback_url))
            log.info(s"Callback url ${callback_url} is now contained in subscribers list.")
          event
        // we don't check None case, because exceptions has already been thrown thrown if None.
      } pipeTo sender

    case event@Unsubscribe(_, callback_url, _) =>
      remove(callback_url).map {
        case Some(subscribers) =>
          if (!subscribers.callback_urls.contains(callback_url))
            log.info(s"Callback url ${callback_url} is now not contained in subscribers list.")
          event
        // we don't check None case, because exceptions has already been thrown if None.
      } pipeTo sender

    case GetSubscribers =>
      store.fetch(SUBSCRIBERS).map {
        urls =>
          // extract set of callback urls from Future[Option[EventSubscribers]]
          urls.map(_.callback_urls).getOrElse(Set.empty[String])
      }.map(_.toList) pipeTo sender
  }

  private def add(callback_url: String) = store.modify(SUBSCRIBERS) {
    subscribers =>
      if (!subscribers.callback_urls.contains(callback_url)) {
        subscribers.callback_urls = subscribers.callback_urls + callback_url
      } else {
        log.warning(s"Callback url ${callback_url} has been already registered. So it will not be added actually.")
      }
      subscribers
  }

  private def remove(callback_url: String) = store.modify(SUBSCRIBERS) {
    subscribers =>
      if (subscribers.callback_urls.contains(callback_url)) {
        subscribers.callback_urls = subscribers.callback_urls - callback_url
      } else {
        log.warning(s"Callback url ${callback_url} has not been registered yet. So it will not be removed actually.")
      }
      subscribers
  }
}

object SubscribersKeeperActor {

  case object GetSubscribers

  final val SUBSCRIBERS = "http_event_subscribers"
}