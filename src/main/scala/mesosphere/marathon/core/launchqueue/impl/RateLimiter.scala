package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock
import java.util.concurrent.TimeUnit

import mesosphere.marathon.state.{ RunSpec, PathId, Timestamp }
import mesosphere.util.DurationToHumanReadable
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
  * Manages the task launch delays for every run spec and config version.
  *
  * We do not keep the delays for every version because that would include scaling changes or manual restarts.
  */
private[launchqueue] class RateLimiter(clock: Clock) {
  import RateLimiter._

  /** The task launch delays per run spec and their last config change. */
  private[this] var taskLaunchDelays = Map[(PathId, Timestamp), Delay]()

  /** Reset delays for tasks that have reached the maximum launch delay threshold. */
  def cleanUpOverdueDelays(): Unit = {
    val now = clock.now()
    taskLaunchDelays = taskLaunchDelays.filter {
      case (_, delay) =>
        now <= delay.referenceTimestamp + delay.maxLaunchDelay
    }
  }

  def getDeadline(spec: RunSpec): Timestamp =
    taskLaunchDelays.get(spec.id -> spec.versionInfo.lastConfigChangeVersion).map(_.deadline) getOrElse clock.now()

  def addDelay(spec: RunSpec): Timestamp = {
    setNewDelay(spec, "Increasing delay") {
      case Some(delay) => delay.increased(clock, spec)
      case None => Delay(clock, spec)
    }
  }

  private[this] def setNewDelay(spec: RunSpec, message: String)(calcDelay: Option[Delay] => Delay): Timestamp = {
    val maybeDelay: Option[Delay] = taskLaunchDelays.get(spec.id -> spec.versionInfo.lastConfigChangeVersion)
    val newDelay = calcDelay(maybeDelay)

    val now: Timestamp = clock.now()
    val timeLeft = (now until newDelay.deadline).toHumanReadable

    log.info(
      s"$message. Task launch delay for [${spec.id} - ${spec.versionInfo.lastConfigChangeVersion}] is set to $timeLeft")
    taskLaunchDelays += ((spec.id -> spec.versionInfo.lastConfigChangeVersion) -> newDelay)
    newDelay.deadline
  }

  def resetDelay(runSpec: RunSpec): Unit = {
    val key = runSpec.id -> runSpec.versionInfo.lastConfigChangeVersion
    taskLaunchDelays.get(key).foreach { _ =>
      log.info(s"Task launch delay for [${runSpec.id} - ${runSpec.versionInfo.lastConfigChangeVersion}}] reset to zero")
      taskLaunchDelays -= key
    }
  }

  def advanceDelay(runSpec: RunSpec): Unit = {
    val key = runSpec.id -> runSpec.versionInfo.lastConfigChangeVersion
    taskLaunchDelays.get(key).foreach { delay =>
      log.info(s"Task launch delay for [${runSpec.id} - ${runSpec.versionInfo.lastConfigChangeVersion}}] got advanced")
      taskLaunchDelays += key -> Delay(clock, delay.currentDelay, delay.maxLaunchDelay)
    }
  }
}

private object RateLimiter {
  private val log = LoggerFactory.getLogger(getClass.getName)

  private object Delay {
    def apply(clock: Clock, runSpec: RunSpec): Delay = {
      val delay = runSpec.backoffStrategy.backoff min runSpec.backoffStrategy.maxLaunchDelay
      Delay(clock.now(), delay, runSpec.backoffStrategy.maxLaunchDelay)
    }
    def apply(clock: Clock, currentDelay: FiniteDuration, maxLaunchDelay: FiniteDuration): Delay =
      Delay(clock.now(), currentDelay, maxLaunchDelay)
  }

  private case class Delay(
      referenceTimestamp: Timestamp,
      currentDelay: FiniteDuration,
      maxLaunchDelay: FiniteDuration) {

    def deadline: Timestamp = referenceTimestamp + currentDelay

    def increased(clock: Clock, runSpec: RunSpec): Delay = {
      val newDelay: FiniteDuration =
        runSpec.backoffStrategy.maxLaunchDelay min FiniteDuration(
          (currentDelay.toNanos * runSpec.backoffStrategy.factor).toLong, TimeUnit.NANOSECONDS)
      Delay(clock, newDelay, runSpec.backoffStrategy.maxLaunchDelay)
    }
  }
}
