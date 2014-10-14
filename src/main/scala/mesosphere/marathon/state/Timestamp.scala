package mesosphere.marathon.state

import org.joda.time.{ DateTime, DateTimeZone }
import scala.math.Ordered

/**
  * An ordered wrapper for UTC timestamps.
  */
case class Timestamp(dateTime: DateTime) extends Ordered[Timestamp] {

  val time = dateTime.toDateTime(DateTimeZone.UTC)

  override def equals(obj: Any): Boolean = obj match {
    case that: Timestamp => this.time == that.time
    case _               => false
  }

  def compare(that: Timestamp): Int = this.time compareTo that.time

  override def toString: String = time.toString
}

object Timestamp {

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

}
