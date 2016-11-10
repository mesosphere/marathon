package mesosphere.marathon
package core.event.impl.callback

import akka.actor.Actor
import akka.pattern.pipe
import mesosphere.marathon.core.event.impl.callback.SubscribersKeeperActor._
import mesosphere.marathon.core.event.{ EventSubscribers, MarathonSubscriptionEvent, Subscribe, Unsubscribe }
import mesosphere.marathon.storage.repository.EventSubscribersRepository
import mesosphere.marathon.util.WorkQueue
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.Future

class SubscribersKeeperActor(val store: EventSubscribersRepository) extends Actor {
  private val lock = WorkQueue("Subscribers", maxConcurrent = 1, maxQueueLength = Int.MaxValue)
  import context.dispatcher
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {

    case event @ Subscribe(_, callbackUrl, _, _) =>
      val addResult: Future[EventSubscribers] = add(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        addResult.map { subscribers =>
          if (subscribers.urls.contains(callbackUrl))
            log.info(s"Callback $callbackUrl subscribed.")
          event
        }(context.dispatcher)

      subscription pipeTo sender()

    case event @ Unsubscribe(_, callbackUrl, _, _) =>
      val removeResult: Future[EventSubscribers] = remove(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        removeResult.map { subscribers =>
          if (!subscribers.urls.contains(callbackUrl))
            log.info(s"Callback $callbackUrl unsubscribed.")
          event
        }(context.dispatcher)

      import context.dispatcher
      subscription pipeTo sender()

    case GetSubscribers =>
      val subscription = store.get().map(_.getOrElse(EventSubscribers()))(context.dispatcher)
      import context.dispatcher
      subscription pipeTo sender()
  }

  @SuppressWarnings(Array("all")) // async/await
  protected[this] def add(callbackUrl: String): Future[EventSubscribers] =
    lock {
      async {
        val subscribers = await(store.get()).getOrElse(EventSubscribers())
        val updated = if (subscribers.urls.contains(callbackUrl)) {
          log.info(s"Existing callback $callbackUrl resubscribed.")
          subscribers
        } else EventSubscribers(subscribers.urls + callbackUrl)

        if (updated != subscribers) {
          await(store.store(updated))
        }
        updated
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  protected[this] def remove(callbackUrl: String): Future[EventSubscribers] =
    lock {
      async {
        val subscribers = await(store.get()).getOrElse(EventSubscribers())
        val updated = if (subscribers.urls.contains(callbackUrl)) {
          EventSubscribers(subscribers.urls - callbackUrl)
        } else {
          log.warn(s"Attempted to unsubscribe nonexistent callback $callbackUrl")
          subscribers
        }
        if (updated != subscribers) {
          await(store.store(updated))
        }
        updated
      }
    }
}

object SubscribersKeeperActor {

  case object GetSubscribers
}
