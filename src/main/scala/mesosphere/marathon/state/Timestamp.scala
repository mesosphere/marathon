package mesosphere.marathon
package state

import java.time.format.{ DateTimeFormatter, DateTimeParseException }
import java.time.{ Duration, Instant, OffsetDateTime, ZoneOffset }
import java.util.concurrent.TimeUnit

import org.apache.mesos

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.math.Ordered
import scala.util.{ Failure, Success, Try }

/**
  * An ordered wrapper for UTC timestamps.
  */
abstract case class Timestamp private (private val instant: Instant) extends Ordered[Timestamp] {
  def toOffsetDateTime: OffsetDateTime =
    OffsetDateTime.ofInstant(
      instant,
      ZoneOffset.UTC)

  def compare(that: Timestamp): Int = this.instant compareTo that.instant

  def before(that: Timestamp): Boolean = (this.instant compareTo that.instant) < 0
  def after(that: Timestamp): Boolean = (this.instant compareTo that.instant) > 0
  def youngerThan(that: Timestamp): Boolean = this.after(that)
  def olderThan(that: Timestamp): Boolean = this.before(that)

  override def toString: String = Timestamp.formatter.format(instant)

  def toInstant: Instant = instant

  def millis: Long = toInstant.toEpochMilli
  def micros: Long = TimeUnit.MILLISECONDS.toMicros(millis)
  def nanos: Long = TimeUnit.MILLISECONDS.toNanos(millis)

  def until(other: Timestamp): FiniteDuration = {
    val duration = Duration.between(this.instant, other.instant)
    FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
  }

  /**
    * @return true if this timestamp is more than "by" duration older than other timestamp.
    */
  def expired(other: Timestamp, by: FiniteDuration): Boolean = this.until(other) > by

  def +(duration: FiniteDuration): Timestamp = Timestamp(instant.plusMillis(duration.toMillis))
  def -(duration: FiniteDuration): Timestamp = Timestamp(instant.minusMillis(duration.toMillis))
}

object Timestamp {
  def apply(offsetDateTime: OffsetDateTime): Timestamp =
    apply(offsetDateTime.toInstant)

  /**
    * Returns a new Timestamp representing the instant that is the supplied
    * dateTime converted to UTC.
    */
  def apply(instant: Instant): Timestamp = new Timestamp(instant) {} // linter:ignore TypeToType

  /**
    * Returns a new Timestamp representing the instant that is the supplied
    * number of milliseconds after the epoch.
    */
  def apply(ms: Long): Timestamp = Timestamp(Instant.ofEpochMilli(ms))

  /**
    * Returns a new Timestamp representing the supplied time.
    */
  def apply(time: String): Timestamp = Timestamp(Try(OffsetDateTime.parse(time)) match {
    case Success(parsed) => parsed
    case Failure(e: DateTimeParseException) => throw new IllegalArgumentException(s"Invalid timestamp provided '$time'. Expecting ISO-8601 datetime string.", e)
    case Failure(e) => throw e
  })

  /**
    * Returns a new Timestamp representing the current instant.
    */
  def now(): Timestamp = Timestamp(Instant.now())

  def now(clock: java.time.Clock): Timestamp =
    Timestamp(Instant.now(clock))

  def zero: Timestamp = Timestamp(0)

  /**
    * Convert Mesos TimeInfo to Timestamp.
    * @return Timestamp for TimeInfo
    */
  implicit def toTimestamp(timeInfo: mesos.Protos.TimeInfo): Timestamp = {
    apply(TimeUnit.NANOSECONDS.toMillis(timeInfo.getNanoseconds))
  }

  /**
    * Convert Mesos TaskStatus to Timestamp.
    * @return Timestamp based on the timestamp (in seconds) from the given TaskStatus
    */
  def fromTaskStatus(taskStatus: mesos.Protos.TaskStatus): Timestamp = {
    apply(TimeUnit.SECONDS.toMillis(taskStatus.getTimestamp.toLong))
  }

  /*
   * .toString in java.time is truncating zeros in millis part, so we use custom formatter to keep them
   */
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
}
