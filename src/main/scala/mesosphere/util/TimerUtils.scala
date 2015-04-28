package mesosphere.util

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{ MetricRegistry, Timer }

/**
  * Utils for timer metrics collection.
  */
object TimerUtils {
  class ScalaTimer(val timer: Timer) {
    def apply[T](block: => T): T = {
      val startTime = System.nanoTime()
      try {
        block
      }
      finally {
        timer.update(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
      }
    }
  }

  def timer[T](registry: MetricRegistry, clazz: Class[T], name: String): ScalaTimer = {
    val wrappedTimer = registry.timer(MetricRegistry.name(clazz, name))
    new ScalaTimer(wrappedTimer)
  }
}
