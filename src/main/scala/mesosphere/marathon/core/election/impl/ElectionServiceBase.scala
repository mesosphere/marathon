package mesosphere.marathon
package core.election.impl

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.pattern.after
import com.codahale.metrics.{ Gauge, MetricRegistry }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.{ ControlThrowable, NonFatal }

private[impl] object ElectionServiceBase {
  protected type Abdicator = /* error: */ Boolean => Unit

  sealed trait State {
    def getCandidate: Option[ElectionCandidate] = this match {
      case Idle(c) => c
      case Leading(c, _) => Some(c)
      case Abdicating(c, _, _) => Some(c)
      case Offering(c) => Some(c)
      case Offered(c) => Some(c)
    }
  }

  case class Idle(candidate: Option[ElectionCandidate]) extends State
  case class Leading(candidate: ElectionCandidate, abdicate: Abdicator) extends State
  case class Abdicating(
    candidate: ElectionCandidate,
    reoffer: Boolean,
    candidateWasStarted: Boolean = false) extends State
  case class Offering(candidate: ElectionCandidate) extends State
  case class Offered(candidate: ElectionCandidate) extends State
}

abstract class ElectionServiceBase(
    system: ActorSystem,
    eventStream: EventStream,
    metrics: Metrics = new Metrics(new MetricRegistry),
    backoff: Backoff,
    shutdownHooks: ShutdownHooks) extends ElectionService with StrictLogging {
  import ElectionServiceBase._

  private[impl] var state: State = Idle(candidate = None)

  protected implicit val executionContext: ExecutionContext = ExecutionContext.global

  def leaderHostPortImpl: Option[String]

  val getLeaderDataTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "current-leader-host-port"))

  final override def leaderHostPort: Option[String] = getLeaderDataTimer {
    synchronized {
      try {
        leaderHostPortImpl
      } catch {
        case NonFatal(e) =>
          logger.error("Could not get current leader", e)
          None
      }
    }
  }

  override def isLeader: Boolean = synchronized {
    state match {
      case Leading(_, _) => true
      case _ => false
    }
  }

  override def abdicateLeadership(error: Boolean = false, reoffer: Boolean = false): Unit = synchronized {
    state match {
      case Leading(candidate, abdicate) =>
        logger.info(s"Abdicating leadership while leading (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
        abdicate(error)
      case Abdicating(candidate, alreadyReoffering, candidateWasStarted) =>
        logger.info("Abdicating leadership while already in process of abdicating" +
          s" (reoffer=${alreadyReoffering || reoffer})")
        state = Abdicating(candidate, alreadyReoffering || reoffer, candidateWasStarted)
      case Offering(candidate) =>
        logger.info(s"Canceling leadership offer waiting for backoff (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
      case Offered(candidate) =>
        logger.info(s"Abdicating leadership while candidating (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
      case Idle(candidate) =>
        logger.info(s"Abdicating leadership while being NO candidate (reoffer=$reoffer)")
        if (reoffer) {
          candidate match {
            case None => logger.error("Cannot reoffer leadership without being a leadership candidate")
            case Some(c) => offerLeadership(c)
          }
        }
    }
  }

  protected def offerLeadershipImpl(): Unit

  private def setOfferState(offeringCase: => Unit, idleCase: => Unit): Unit = synchronized {
    state match {
      case Abdicating(candidate, reoffer, candidateWasStarted) =>
        logger.error("Will reoffer leadership after abdicating")
        state = Abdicating(candidate, reoffer = true, candidateWasStarted)
      case Leading(candidate, abdicate) =>
        logger.info("Ignoring leadership offer while being leader")
      case Offering(_) =>
        offeringCase
      case Offered(_) =>
        logger.info("Ignoring repeated leadership offer")
      case Idle(_) =>
        idleCase
    }
  }

  final override def offerLeadership(candidate: ElectionCandidate): Unit = synchronized {
    if (shutdownHooks.isShuttingDown) {
      logger.info("Ignoring leadership offer while shutting down")
    } else {
      setOfferState(
        offeringCase = {
        // some offering attempt is running
        logger.info("Ignoring repeated leadership offer")
      },
        idleCase = {
        // backoff idle case
        logger.info(s"Will offer leadership after ${backoff.value()} backoff")
        state = Offering(candidate)
        after(backoff.value(), system.scheduler)(Future {
          synchronized {
            setOfferState(
              offeringCase = {
              // now after backoff actually set Offered state
              state = Offered(candidate)
              offerLeadershipImpl()
            },
              idleCase = {
              // state became Idle meanwhile
              logger.info("Canceling leadership offer attempt")
            })
          }
        })
      })
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  protected def stopLeadership(): Unit = synchronized {
    val (candidate, reoffer, candidateWasStarted) = state match {
      case Leading(c, a) => (c, false, false)
      case Abdicating(c, ro, cws) => (c, ro, cws)
      case Offered(c) => (c, false, false)
      case Offering(c) => (c, false, false)
      case Idle(c) => (c.get, false, false)
    }
    state = Idle(Some(candidate))

    if (!candidateWasStarted) {
      // Our leadership has been defeated. Tell the candidate and the world
      candidate.stopLeadership()
      eventStream.publish(LocalLeadershipEvent.Standby)
      stopMetrics()
    }

    // call abdication continuations
    if (reoffer) {
      offerLeadership(candidate)
    }
  }

  @SuppressWarnings(Array("CatchFatal", "CatchThrowable", "OptionGet"))
  protected def startLeadership(abdicate: Abdicator): Unit = synchronized {
    def backoffAbdicate(error: Boolean) = {
      if (error) backoff.increase()
      abdicate(error)
    }

    state match {
      case Abdicating(candidate, reoffer, _) =>
        logger.info("Became leader and abdicating immediately")
        state = Abdicating(candidate, reoffer, candidateWasStarted = true)
        abdicate
      case _ =>
        val candidate = state.getCandidate.get // Idle(None) is not possible
        state = Leading(candidate, backoffAbdicate)
        try {
          // Start the leader duration metric
          startMetrics()

          candidate.startLeadership()

          // tell the world about us
          eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)

          // We successfully took over leadership. Time to reset backoff. Check that we still are leader.
          if (isLeader) {
            backoff.reset()
          }
        } catch {
          case NonFatal(e) => // catch Scala and Java exceptions
            logger.error("Failed to take over leadership", e)
            abdicateLeadership(error = true)
          case ex: ControlThrowable => // scala uses exceptions to control flow. Those exceptions need to be propagated
            throw ex
          case ex: Throwable => // all other exceptions here are fatal errors, that can not be handled.
            logger.error("Fatal error while trying to take over leadership. Exit now.", ex)
            abdicateLeadership(error = true)
            Runtime.getRuntime.asyncExit()
        }
    }
  }

  /**
    * Subscribe to leadership change events.
    *
    * The given actorRef will initally get the current state via the appropriate
    * [[LocalLeadershipEvent]] message and will be informed of changes after that.
    */
  override def subscribe(self: ActorRef): Unit = {
    eventStream.subscribe(self, classOf[LocalLeadershipEvent])
    val currentState = if (isLeader) LocalLeadershipEvent.ElectedAsLeader else LocalLeadershipEvent.Standby
    self ! currentState
  }

  /** Unsubscribe to any leadership change events to this actor ref. */
  override def unsubscribe(self: ActorRef): Unit = {
    eventStream.unsubscribe(self, classOf[LocalLeadershipEvent])
  }

  private def startMetrics(): Unit = {
    metrics.gauge("service.mesosphere.marathon.leaderDuration", new Gauge[Long] {
      val startedAt = System.currentTimeMillis()

      override def getValue: Long = {
        System.currentTimeMillis() - startedAt
      }
    })
  }

  private def stopMetrics(): Unit = {
    metrics.registry.remove("service.mesosphere.marathon.leaderDuration")
  }
}
