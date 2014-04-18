package mesosphere.marathon.event.exec

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import mesosphere.marathon.event.{
  MarathonSubscriptionEvent,
  Unsubscribe,
  Subscribe
}
import mesosphere.marathon.event.exec.SubscribersKeeperActor._
import mesosphere.marathon.state.MarathonStore
import scala.concurrent.Future

class SubscribersKeeperActor(val store: MarathonStore[EventSubscribers]) extends Actor with ActorLogging {

  implicit val ec = ExecEventModule.executionContext

  override def receive = {

    case event @ Subscribe(_, execCmd, _) => {
      val addResult: Future[Option[EventSubscribers]] = add(execCmd)

      val subscribers: Future[MarathonSubscriptionEvent] =
        addResult.collect { case Some(subscribers) =>
          if (subscribers.cmds.contains(execCmd))
            log.info("Callback [%s] subscribed." format execCmd)
          event
        }

      subscribers pipeTo sender
    }

    case event @ Unsubscribe(_, execCmd, _) => {
      val removeResult: Future[Option[EventSubscribers]] = remove(execCmd)

      val subscribers: Future[MarathonSubscriptionEvent] =
        removeResult.collect { case Some(subscribers) =>
          if (!subscribers.cmds.contains(execCmd))
            log.info("Callback [%s] unsubscribed." format execCmd)
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

  protected[this] def add(execCmd: String): Future[Option[EventSubscribers]] =
    store.modify(SUBSCRIBERS) { deserialize =>
      val existingSubscribers = deserialize()
      if (existingSubscribers.cmds.contains(execCmd)) {
        log.info("Existing callback [%s] resubscribed." format execCmd)
        existingSubscribers
      }
      else EventSubscribers(existingSubscribers.cmds + execCmd)
    }

  protected[this] def remove(execCmd: String): Future[Option[EventSubscribers]] =
    store.modify(SUBSCRIBERS) { deserialize =>
      val existingSubscribers = deserialize()

      if (existingSubscribers.cmds.contains(execCmd))
        EventSubscribers(existingSubscribers.cmds - execCmd)

      else {
        log.warning("Attempted to unsubscribe nonexistent callback [%s]." format execCmd)
        existingSubscribers
      }
    }
}

object SubscribersKeeperActor {

  case object GetSubscribers

  final val SUBSCRIBERS = "exec_event_subscribers"
}
