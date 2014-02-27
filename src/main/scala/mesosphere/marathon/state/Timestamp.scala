package mesosphere.marathon.state

import mesosphere.marathon.api.FieldConstraints.FieldJsonValue
import org.joda.time.{DateTime, DateTimeZone}
import scala.math.Ordered

/**
 * An ordered wrapper for timestamps.
 */
case class Timestamp(time: DateTime) extends Ordered[Timestamp] {
  def compare(that: Timestamp) = this.time compareTo that.time
  override def toString(): String = time.toString
}

object Timestamp {

  /**
   * Returns a new Timestamp representing the instant that is the supplied
   * number of milliseconds after the epoch.
   */
  def apply(ms: Long): Timestamp =
    Timestamp(new DateTime(ms).toDateTime(DateTimeZone.UTC))

  /**
   * Returns a new Timestamp representing the supplied time.
   *
   * See the Joda time documentation for a description of acceptable formats:
   * http://joda-time.sourceforge.net/apidocs/org/joda/time/format/ISODateTimeFormat.html#dateTimeParser()
   */
  def apply(time: String): Timestamp =
    Timestamp(DateTime.parse(time).toDateTime(DateTimeZone.UTC))

  /**
   * Returns a new Timestamp representing the current instant.
   */
  def now(): Timestamp = Timestamp(System.currentTimeMillis)

}
