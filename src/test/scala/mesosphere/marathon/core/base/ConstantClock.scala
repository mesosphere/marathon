package mesosphere.marathon.core.base

import mesosphere.marathon.state.Timestamp
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

case class ConstantClock(var now_ : Timestamp = Timestamp(new DateTime(2015, 4, 9, 12, 30))) extends Clock {
  def now(): Timestamp = now_

  def +=(duration: FiniteDuration): Unit = now_ += duration
}
