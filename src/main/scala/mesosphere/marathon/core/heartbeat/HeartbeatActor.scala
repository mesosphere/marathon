package mesosphere.marathon.core.heartbeat

import akka.actor._
import scala.concurrent.duration._

/**
  * HeartbeatActor monitors the heartbeat of some process and executes handlers for various conditions.
  * If an expected heartbeat is missed then execute an `onSkip` handler.
  * If X number of subsequent heartbeats are missed then execute an `onFailure` handler and become inactive.
  * Upon creation the actor is in an inactive state and must be sent a MessageActivate message to activate.
  * Once activated the actor will monitor for MessagePulse messages (these are the heartbeats).
  * The actor may be forcefully deactivated by sending it an MessageDeactivate message.
  */
class HeartbeatActor(config: Heartbeat.Config) extends LoggingFSM[HeartbeatInternal.State, HeartbeatInternal.Data] {
  import Heartbeat._
  import HeartbeatInternal._

  startWith(StateInactive, DataNone)

  when(StateInactive) {
    case Event(MessageActivate(reactor, token), DataNone) =>
      log.debug("heartbeat activated")
      goto(StateActive) using DataActive(config.withReactor(reactor), token)
    case _ =>
      stay // swallow all other event types
  }

  when(StateActive, stateTimeout = config.heartbeatTimeout) {
    case Event(MessagePulse, data: DataActive) =>
      stay using data.copy(missed = 0)

    case Event(StateTimeout, data: DataActive) =>
      val missed = data.missed + 1
      if (missed >= config.missedHeartbeatsThreshold) {
        data.reactor.onFailure()
        goto(StateInactive) using DataNone
      } else {
        data.reactor.onSkip(missed)
        stay using data.copy(missed = missed)
      }

    case Event(MessageDeactivate(token), data: DataActive) =>
      // only deactivate if token == data.sessionToken
      if (token.eq(data.sessionToken)) {
        log.debug("heartbeat deactivated")
        goto(StateInactive) using DataNone
      } else {
        stay
      }

    case Event(MessageActivate(newReactor, newToken), data: DataActive) =>
      log.debug("heartbeat re-activated")
      stay using DataActive(reactor = config.withReactor(newReactor), sessionToken = newToken)
  }

  whenUnhandled{
    case Event(e, d) =>
      log.warning("unhandled event {} in state {}/{}", e, stateName, d)
      stay
  }

  log.debug("starting heartbeat actor")

  initialize()
}

object Heartbeat {
  import org.slf4j.LoggerFactory

  case class Config(
      heartbeatTimeout: FiniteDuration,
      missedHeartbeatsThreshold: Int,
      reactorDecorator: Option[Reactor.Decorator] = Some(Reactor.withLogging()())) {

    /** withReactor applies the optional reactorDecorator */
    def withReactor: Reactor.Decorator = Reactor.Decorator { r =>
      reactorDecorator.map(_(r)).getOrElse(r)
    }
  }

  sealed trait Message
  case object MessagePulse extends Message
  case class MessageDeactivate(sessionToken: AnyRef) extends Message
  case class MessageActivate(reactor: Reactor, sessionToken: AnyRef) extends Message

  trait Reactor {
    def onSkip(skipped: Int): Unit
    def onFailure(): Unit
  }

  object Reactor {

    /** Decorator generates a modified Reactor with enhanced functionality */
    trait Decorator extends Function1[Reactor, Reactor]

    object Decorator {
      def apply(f: Reactor => Reactor): Decorator = new Decorator {
        override def apply(r: Reactor): Reactor = f(r)
      }
    }

    /**
      * withLogging decorates the given Reactor by logging messages prior to forwarding each callback
      */
    final def withLogging(
      skipLogger: String => Unit = log.debug)(
      failureLogger: String => Unit = log.debug): Decorator = Decorator { r =>
      new Reactor {
        def onSkip(skipped: Int): Unit = {
          skipLogger("detected skipped heartbeat")
          r.onSkip(skipped)
        }

        def onFailure(): Unit = {
          // might be a little redundant (depending what is logged elsewhere) but this is a
          // pretty important event that we don't want to miss
          failureLogger("detected heartbeat failure")
          r.onFailure()
        }
      }
    }
  }

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  def props(config: Config): Props = Props(classOf[HeartbeatActor], config)
}

private[heartbeat] object HeartbeatInternal {
  import Heartbeat._

  sealed trait State
  case object StateInactive extends State
  case object StateActive extends State

  sealed trait Data
  case object DataNone extends Data

  /** @constructor capture the state of an active heartbeat monitor */
  case class DataActive(
    reactor: Reactor,
    sessionToken: AnyRef,
    missed: Int = 0) extends Data
}
