package mesosphere.marathon
package core.election.impl

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ ExecutorService, Executors }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.async.ContextPropagatingExecutionContextWrapper
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }

import scala.async.Async
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * This is a somewhat dummy implementation of [[ElectionService]]. It is used
  * when the high-availability mode is disabled.
  *
  * It stops Marathon when leadership is abdicated.
  */
class PseudoElectionService(
  hostPort: String,
  system: ActorSystem,
  override val eventStream: EventStream,
  lifecycleState: LifecycleState,
  crashStrategy: CrashStrategy)
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  system.registerOnTermination {
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  }

  private[this] val exitTimeoutOnAbdication: FiniteDuration = 500.milliseconds

  private val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /** We re-use the single thread executor here because some methods of this class might get blocked for a long time. */
  private implicit val ec: ExecutionContext = ContextPropagatingExecutionContextWrapper(
    ExecutionContext.fromExecutor(threadExecutor))

  /* it is made `private[impl]` because it has to be accessible from inside the corresponding unit-tests. */
  private[impl] val currentCandidate = new AtomicReference(Option.empty[ElectionCandidate])
  private[this] val isCurrentlyLeading = new AtomicBoolean(false)

  // This variable is initialized with `false` and can only be set to `true` later.
  private[this] val leadershipOffered = new AtomicBoolean(false)

  override def isLeader: Boolean = isCurrentlyLeading.get
  override def localHostPort: String = hostPort

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    if (isLeader) Some(hostPort) else None
  }

  override def offerLeadership(candidate: ElectionCandidate): Unit = {
    logger.info(s"$candidate offered leadership")
    if (leadershipOffered.compareAndSet(false, true)) {
      if (lifecycleState.isRunning) {
        logger.info("Going to acquire leadership")
        currentCandidate.set(Some(candidate))
        Async.async {
          try {
            startLeadership()
            isCurrentlyLeading.set(true)
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Fatal error while acquiring leadership for $candidate. Exiting now", ex)
              stop(exit = true)
          }
        }
      } else {
        logger.info("Not accepting the leadership offer since Marathon is shutting down")
      }
    } else {
      logger.error(s"Got another leadership offer from $candidate. Exiting now")
      stop(exit = true)
    }
  }

  override def abdicateLeadership(): Unit = {
    logger.info("Abdicating leadership")
    stop(exit = true, exitTimeout = exitTimeoutOnAbdication)
  }

  private def stop(exit: Boolean, exitTimeout: FiniteDuration = 0.milliseconds): Unit = {
    logger.info("Stopping the election service")
    isCurrentlyLeading.set(false)
    try {
      stopLeadership()
    } catch {
      case NonFatal(ex) =>
        logger.error("Fatal error while stopping", ex)
    } finally {
      currentCandidate.set(None)
      if (exit) {
        logger.info("Terminating due to leadership abdication or failure")
        system.scheduler.scheduleOnce(exitTimeout) {
          crashStrategy.crash()
        }
      }
    }
  }

  private def startLeadership(): Unit = {
    currentCandidate.get.foreach(startCandidateLeadership)
    startMetrics()
  }

  private def stopLeadership(): Unit = {
    stopMetrics()
    currentCandidate.get.foreach(stopCandidateLeadership)
  }

  private[this] val candidateLeadershipStarted = new AtomicBoolean(false)
  private def startCandidateLeadership(candidate: ElectionCandidate): Unit = {
    if (candidateLeadershipStarted.compareAndSet(false, true)) {
      logger.info(s"Starting $candidate's leadership")
      candidate.startLeadership()
      logger.info(s"Started $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)
    }
  }

  private def stopCandidateLeadership(candidate: ElectionCandidate): Unit = {
    if (candidateLeadershipStarted.compareAndSet(true, false)) {
      logger.info(s"Stopping $candidate's leadership")
      candidate.stopLeadership()
      logger.info(s"Stopped $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.Standby)
    }
  }
}
