package mesosphere.marathon
package state

import java.time.{ Instant, OffsetDateTime }
import java.util.concurrent.TimeUnit

import org.apache.mesos
import org.joda.time.{ DateTime, DateTimeZone }

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.math.Ordered

/**
  * An ordered wrapper for UTC timestamps.
  */
abstract case class Timestamp private (private val utcDateTime: DateTime) extends Ordered[Timestamp] {
  def toOffsetDateTime: OffsetDateTime =
    OffsetDateTime.ofInstant(
      Instant.ofEpochMilli(utcDateTime.toInstant.getMillis),
      utcDateTime.getZone.toTimeZone.toZoneId)

  def compare(that: Timestamp): Int = this.utcDateTime compareTo that.utcDateTime

  def before(that: Timestamp): Boolean = (this.utcDateTime compareTo that.utcDateTime) < 0
  def after(that: Timestamp): Boolean = (this.utcDateTime compareTo that.utcDateTime) > 0
  def youngerThan(that: Timestamp): Boolean = this.after(that)
  def olderThan(that: Timestamp): Boolean = this.before(that)

  override def toString: String = utcDateTime.toString

  def toDateTime: DateTime = utcDateTime

  def millis: Long = toDateTime.getMillis
  def micros: Long = TimeUnit.MILLISECONDS.toMicros(millis)
  def nanos: Long = TimeUnit.MILLISECONDS.toNanos(millis)

  def until(other: Timestamp): FiniteDuration = {
    val millis = other.utcDateTime.getMillis - utcDateTime.getMillis
    FiniteDuration(millis, TimeUnit.MILLISECONDS)
  }

  /**
    * @return true if this timestamp is more than "by" duration older than other timestamp.
    */
  def expired(other: Timestamp, by: FiniteDuration): Boolean = this.until(other) > by

  def +(duration: FiniteDuration): Timestamp = Timestamp(utcDateTime.getMillis + duration.toMillis)
  def -(duration: FiniteDuration): Timestamp = Timestamp(utcDateTime.getMillis - duration.toMillis)
}

object Timestamp {
  def apply(offsetDateTime: OffsetDateTime): Timestamp =
    apply(offsetDateTime.toInstant.toEpochMilli)

  /**
    * Returns a new Timestamp representing the instant that is the supplied
    * dateTime converted to UTC.
    */
  def apply(dateTime: DateTime): Timestamp = new Timestamp(dateTime.toDateTime(DateTimeZone.UTC)) {} // linter:ignore TypeToType

  /**
    * Returns a new Timestamp representing the instant that is the supplied
    * number of milliseconds after the epoch.
    */
  def apply(ms: Long): Timestamp = Timestamp(new DateTime(ms))

  /**
    * Returns a new Timestamp representing the supplied time.
    *
    * See the Joda time documentation for a description of acceptable formats:
    * http://joda-time.sourceforge.net/apidocs/org/joda/time/format/ISODateTimeFormat.html#dateTimeParser()
    */
  def apply(time: String): Timestamp = Timestamp(DateTime.parse(time))

  /**
    * Returns a new Timestamp representing the current instant.
    */
  def now(): Timestamp = Timestamp(System.currentTimeMillis)

  def now(clock: java.time.Clock): Timestamp =
    Timestamp(clock.millis())

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
}
