package mesosphere.marathon.core.election.impl

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.pattern.after
import com.codahale.metrics.{ Gauge, MetricRegistry }
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{ CurrentRuntime, ShutdownHooks }
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.slf4j.LoggerFactory

import scala.concurrent.Future
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
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    metrics: Metrics = new Metrics(new MetricRegistry),
    backoff: Backoff,
    shutdownHooks: ShutdownHooks) extends ElectionService {
  import ElectionServiceBase._

  private lazy val log = LoggerFactory.getLogger(getClass.getName)

  private[impl] var state: State = Idle(candidate = None)

  import scala.concurrent.ExecutionContext.Implicits.global

  def leaderHostPortImpl: Option[String]

  val getLeaderDataTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "current-leader-host-port"))

  final override def leaderHostPort: Option[String] = getLeaderDataTimer {
    synchronized {
      try {
        leaderHostPortImpl
      } catch {
        case NonFatal(e) =>
          log.error("error while getting current leader", e)
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

  // scalastyle:off cyclomatic.complexity
  override def abdicateLeadership(error: Boolean = false, reoffer: Boolean = false): Unit = synchronized {
    state match {
      case Leading(candidate, abdicate) =>
        log.info(s"Abdicating leadership while leading (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
        abdicate(error)
      case Abdicating(candidate, alreadyReoffering, candidateWasStarted) =>
        log.info("Abdicating leadership while already in process of abdicating" +
          s" (reoffer=${alreadyReoffering || reoffer})")
        state = Abdicating(candidate, alreadyReoffering || reoffer, candidateWasStarted)
      case Offering(candidate) =>
        log.info(s"Canceling leadership offer waiting for backoff (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
      case Offered(candidate) =>
        log.info(s"Abdicating leadership while candidating (reoffer=$reoffer)")
        state = Abdicating(candidate, reoffer)
      case Idle(candidate) =>
        log.info(s"Abdicating leadership while being NO candidate (reoffer=$reoffer)")
        if (reoffer) {
          candidate match {
            case None => log.error("Cannot reoffer leadership without being a leadership candidate")
            case Some(c) => offerLeadership(c)
          }
        }
    }
  }
  // scalastyle:on

  protected def offerLeadershipImpl(): Unit

  private def setOfferState(offeringCase: => Unit, idleCase: => Unit): Unit = synchronized {
    state match {
      case Abdicating(candidate, reoffer, candidateWasStarted) =>
        log.error("Will reoffer leadership after abdicating")
        state = Abdicating(candidate, reoffer = true, candidateWasStarted)
      case Leading(candidate, abdicate) =>
        log.info("Ignoring leadership offer while being leader")
      case Offering(_) =>
        offeringCase
      case Offered(_) =>
        log.info("Ignoring repeated leadership offer")
      case Idle(_) =>
        idleCase
    }
  }

  final override def offerLeadership(candidate: ElectionCandidate): Unit = synchronized {
    if (shutdownHooks.isShuttingDown) {
      log.info("Ignoring leadership offer while shutting down")
    } else {
      setOfferState({
        // some offering attempt is running
        log.info("Ignoring repeated leadership offer")
      }, {
        // backoff idle case
        log.info(s"Will offer leadership after ${backoff.value()} backoff")
        state = Offering(candidate)
        after(backoff.value(), system.scheduler)(Future {
          synchronized {
            setOfferState({
              // now after backoff actually set Offered state
              state = Offered(candidate)
              offerLeadershipImpl()
            }, {
              // state became Idle meanwhile
              log.info("Canceling leadership offer attempt")
            })
          }
        })
      })
    }
  }

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

  protected def startLeadership(abdicate: Abdicator): Unit = synchronized {
    def backoffAbdicate(error: Boolean) = {
      if (error) backoff.increase()
      abdicate(error)
    }

    state match {
      case Abdicating(candidate, reoffer, _) =>
        log.info("Became leader and abdicating immediately")
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
            log.error("Failed to take over leadership", e)
            abdicateLeadership(error = true)
          case ex: ControlThrowable => // scala uses exceptions to control flow. Those exceptions need to be propagated
            throw ex
          case ex: Throwable => // all other exceptions here are fatal errors, that can not be handled.
            log.error("Fatal error while trying to take over leadership. Exit now.", ex)
            abdicateLeadership(error = true)
            CurrentRuntime.asyncExit()
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
