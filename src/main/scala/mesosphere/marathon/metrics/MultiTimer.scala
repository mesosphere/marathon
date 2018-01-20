package mesosphere.marathon
package metrics

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

object MultiTimer {
  case class Timer(label: String) {

    var start: Option[Long] = None
    var end: Option[Long] = None

    def begin(): Unit = {
      start = Some(System.nanoTime())
    }

    def stop(): Unit = {
      end = Some(System.nanoTime())
    }

    def duration(): Option[FiniteDuration] = {
      (end, start) match {
        case (Some(endTime), Some(startTime)) =>
          Some(FiniteDuration(endTime - startTime, TimeUnit.NANOSECONDS))
        case _ => None
      }
    }

    override def toString: String = duration().fold(s"$label=N/A"){ d => s"$label=${d.toMillis}ms" }

  }
}

class MultiTimer {

  val subTimers: ArrayBuffer[MultiTimer.Timer] = ArrayBuffer.empty

  def startSubTimer(label: String): MultiTimer.Timer = {
    val timer = new metrics.MultiTimer.Timer(label)
    timer.begin()
    subTimers += timer
    return timer
  }

  override def toString: String = subTimers.mkString(", ")

}
