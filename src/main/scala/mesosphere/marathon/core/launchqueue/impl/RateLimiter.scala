package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock
import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.state.{ RunSpec, PathId, Timestamp }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
  * Manages the task launch delays for every run spec and config version.
  *
  * We do not keep the delays for every version because that would include scaling changes or manual restarts.
  */
private[launchqueue] class RateLimiter(config: LaunchQueueConfig, clock: Clock) {
  import RateLimiter._

  /** The task launch delays per run spec and their last config change. */
  private[this] var taskLaunchDelays = Map[(PathId, Timestamp), Delay]()

  /**
    * Reset delay for tasks that have reached the viability
    * threshold. The deadline indicates when the task has been
    * launched for the last time.
    */
  def resetDelaysOfViableTasks(): Unit = {
    taskLaunchDelays = taskLaunchDelays.filter {
      case (_, delay) =>
        clock.now() - config.minimumViableTaskExecutionDuration < delay.deadline
    }
  }

  def getDeadline(spec: RunSpec): Timestamp =
    taskLaunchDelays.get(spec.id -> spec.versionInfo.lastConfigChangeVersion).map(_.deadline) getOrElse clock.now()

  def addDelay(spec: RunSpec): Timestamp = {
    setNewDelay(spec, "Increasing delay") {
      case Some(delay) => Some(delay.increased(clock, spec))
      case None => Some(Delay(clock, spec))
    }
  }

  private[this] def setNewDelay(spec: RunSpec, message: String)(
    calcDelay: Option[Delay] => Option[Delay]): Timestamp = {
    val maybeDelay: Option[Delay] = taskLaunchDelays.get(spec.id -> spec.versionInfo.lastConfigChangeVersion)
    calcDelay(maybeDelay) match {
      case Some(newDelay) =>
        import mesosphere.util.DurationToHumanReadable
        val now: Timestamp = clock.now()
        val priorTimeLeft = (now until maybeDelay.map(_.deadline).getOrElse(now)).toHumanReadable
        val timeLeft = (now until newDelay.deadline).toHumanReadable

        if (newDelay.deadline <= now) {
          resetDelay(spec)
        } else {
          log.info(s"$message. Task launch delay for [${spec.id}] changed from [$priorTimeLeft] to [$timeLeft].")
          taskLaunchDelays += ((spec.id, spec.versionInfo.lastConfigChangeVersion) -> newDelay)
        }
        newDelay.deadline

      case None =>
        resetDelay(spec)
        clock.now()
    }
  }

  def resetDelay(runSpec: RunSpec): Unit = {
    if (taskLaunchDelays contains (runSpec.id -> runSpec.versionInfo.lastConfigChangeVersion)) {
      log.info(s"Task launch delay for [${runSpec.id} - ${runSpec.versionInfo.lastConfigChangeVersion}}] reset to zero")
      taskLaunchDelays -= (runSpec.id -> runSpec.versionInfo.lastConfigChangeVersion)
    }
  }
}

private object RateLimiter {
  private val log = LoggerFactory.getLogger(getClass.getName)

  private object Delay {
    def apply(clock: Clock, runSpec: RunSpec): Delay = Delay(clock.now() + runSpec.backoffStrategy.backoff, runSpec.backoffStrategy.backoff)
    def apply(clock: Clock, delay: FiniteDuration): Delay = Delay(clock.now() + delay, delay)
  }

  private case class Delay(
      deadline: Timestamp,
      delay: FiniteDuration) {

    def increased(clock: Clock, runSpec: RunSpec): Delay = {
      val newDelay: FiniteDuration =
        runSpec.backoffStrategy.maxLaunchDelay min FiniteDuration(
          (delay.toNanos * runSpec.backoffStrategy.factor).toLong, TimeUnit.NANOSECONDS)
      Delay(clock, newDelay)
    }
  }
}
