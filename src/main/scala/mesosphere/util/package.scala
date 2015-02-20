package mesosphere

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

package object util {
  implicit class DurationToHumanReadable(val d: Duration) extends AnyVal {
    def toHumanReadable: String = {
      import TimeUnit._

      def appendIfPositive(value: Long, unit: TimeUnit, res: String): String =
        if (value > 0) {
          s"$res $value ${unit.name().toLowerCase}"
        }
        else res

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
            }
            else {
              appendIfPositive(value, unit, res)
            }
          case MICROSECONDS =>
            loop(MILLISECONDS, res)
          case NANOSECONDS =>
            loop(MILLISECONDS, res)
        }
      }

      loop(DAYS).trim
    }
  }
}
