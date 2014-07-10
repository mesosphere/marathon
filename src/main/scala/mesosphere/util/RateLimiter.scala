package mesosphere.util

import mesosphere.marathon.api.v1.AppDefinition

import scala.concurrent.duration.{
  Deadline,
  FiniteDuration,
  HOURS,
  MILLISECONDS
}

import scala.util.Try

class RateLimiter {

  protected case class Delay(
    current: FiniteDuration,
    future: Iterator[FiniteDuration])

  protected[this] val maxLaunchDelay = FiniteDuration(1, HOURS)

  protected[this] var taskLaunchDelays = Map[String, Delay]()

  def getDelay(app: AppDefinition): Deadline =
    taskLaunchDelays.get(app.id).map(_.current.fromNow) getOrElse Deadline.now

  def addDelay(app: AppDefinition) = {
    val newDelay = taskLaunchDelays.get(app.id) match {
      case Some(Delay(current, future)) => Delay(future.next, future)
      case None => Delay(
        app.launchDelay,
        durations(app.launchDelay, app.launchDelayFactor)
      )
    }
    taskLaunchDelays = taskLaunchDelays + (app.id -> newDelay)
  }

  def resetDelay(appId: String): Unit =
    taskLaunchDelays = taskLaunchDelays - appId

  /**
    * Returns an infinite lazy stream of exponentially increasing durations.
    *
    * @param initial  the length of the first duration in the resulting stream
    * @param factor   the multiplier used to compute each successive
    *                 element in the resulting stream
    * @param limit    the maximum length of any duration in the stream
    */
  protected[this] def durations(
    initial: FiniteDuration,
    factor: Double,
    limit: FiniteDuration = maxLaunchDelay): Iterator[FiniteDuration] =
    Iterator.iterate(initial) { interval =>
      Try {
        val millis: Long = (interval.toMillis * factor).toLong
        FiniteDuration(millis, MILLISECONDS) min limit
      }.getOrElse(limit)
    }

}
