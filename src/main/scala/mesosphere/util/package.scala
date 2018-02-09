package mesosphere

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

package object util {

  /**
    * Truncates the string output of a long list.
    *
    * This should be used to reduce the size of logging ids etc.
    *
    * @param it The iterable that will be truncated.
    * @param showFirst The number of items that should be shown in string.
    * @tparam T
    * @return String representation of truncated sequence.
    */
  def summarize[T](it: Iterator[T], showFirst: Int = 3): String = {
    val s = new StringBuilder
    s ++= "Seq("
    s ++= it.take(showFirst).toSeq.mkString(", ")
    if (it.hasNext)
      s ++= s", ... ${it.length} more"
    s ++= ")"
    s.toString
  }

  implicit class DurationToHumanReadable(val d: Duration) extends AnyVal {
    def toHumanReadable: String = {
      import TimeUnit._

      def appendIfPositive(value: Long, unit: TimeUnit, res: String): String =
        if (value > 0) {
          s"$res $value ${unit.name().toLowerCase}"
        } else res

      @tailrec
      def loop(unit: TimeUnit, res: String = ""): String = {
        unit match {
          case DAYS =>
            loop(HOURS, appendIfPositive(d.toDays, unit, res))
          case HOURS =>
            loop(MINUTES, appendIfPositive(d.toHours % 24, unit, res))
          case MINUTES =>
            loop(SECONDS, appendIfPositive(d.toMinutes % 60, unit, res))
          case SECONDS =>
            loop(MILLISECONDS, appendIfPositive(d.toSeconds % 60, unit, res))
          case MILLISECONDS =>
            val value = d.toMillis % 1000
            if (res.isEmpty) {
              s"$value milliseconds"
            } else {
              appendIfPositive(value, unit, res)
            }
          case MICROSECONDS =>
            loop(MILLISECONDS, res)
          case NANOSECONDS =>
            loop(NANOSECONDS, res)
        }
      }

      loop(DAYS).trim
    }
  }
}
