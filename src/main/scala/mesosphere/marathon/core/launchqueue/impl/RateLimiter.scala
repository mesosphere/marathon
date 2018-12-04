package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state.{RunSpec, RunSpecConfigRef, Timestamp}
import mesosphere.util.DurationToHumanReadable

import scala.concurrent.duration._

/**
  * Manages the task launch delays for every run spec and config version.
  *
  * We do not keep the delays for every version because that would include scaling changes or manual restarts.
  */
private[launchqueue] class RateLimiter(clock: Clock) extends StrictLogging {
  import RateLimiter._

  /** The task launch delays per run spec and their last config change. */
  private[this] var taskLaunchDelays = Map[RunSpecConfigRef, Delay]()

  /**
    * Reset delays for tasks that have reached the maximum launch delay threshold.
    *
    * @return List of RunSpecConfigRef's removed
    */
  def cleanUpOverdueDelays(): Seq[RunSpecConfigRef] = {
    val now = clock.now()
    val overdue: List[RunSpecConfigRef] = taskLaunchDelays.collect {
      case (ref, delay) if now > (delay.referenceTimestamp + delay.maxLaunchDelay) =>
        ref
    }(collection.breakOut)

    taskLaunchDelays = taskLaunchDelays -- overdue
    overdue
  }

  def currentDelays: Seq[DelayUpdate] =
    taskLaunchDelays.map {
      case (ref, delay) =>
        DelayUpdate(ref, Some(delay.deadline))
    }(collection.breakOut)

  def getDeadline(ref: RunSpecConfigRef): Option[Timestamp] =
    taskLaunchDelays.get(ref).map { d => d.deadline }

  def addDelay(spec: RunSpec): Timestamp = {
    setNewDelay(spec, "Increasing delay") {
      case Some(delay) => delay.increased(clock, spec)
      case None => Delay(clock, spec)
    }
  }

  private[this] def setNewDelay(spec: RunSpec, message: String)(calcDelay: Option[Delay] => Delay): Timestamp = {
    val maybeDelay: Option[Delay] = taskLaunchDelays.get(spec.configRef)
    val newDelay = calcDelay(maybeDelay)

    val now: Timestamp = clock.now()
    val timeLeft = (now until newDelay.deadline).toHumanReadable

    logger.info(
      s"$message. Task launch delay for [${spec.id} - ${spec.versionInfo.lastConfigChangeVersion}] is set to $timeLeft")
    taskLaunchDelays += (spec.configRef -> newDelay)
    newDelay.deadline
  }

  def resetDelay(runSpec: RunSpec): Unit = {
    val key = runSpec.configRef
    taskLaunchDelays.get(key).foreach { _ =>
      logger.info(s"Task launch delay for [${runSpec.id} - ${runSpec.versionInfo.lastConfigChangeVersion}}] reset to zero")
      taskLaunchDelays -= key
    }
  }

  def advanceDelay(runSpec: RunSpec): Unit = {
    val key = runSpec.configRef
    taskLaunchDelays.get(key).foreach { delay =>
      logger.info(s"Task launch delay for [${runSpec.id} - ${runSpec.versionInfo.lastConfigChangeVersion}}] got advanced")
      taskLaunchDelays += key -> Delay(clock, delay.currentDelay, delay.maxLaunchDelay)
    }
  }
}

object RateLimiter {

  case class DelayUpdate(ref: RunSpecConfigRef, delayUntil: Option[Timestamp])
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
