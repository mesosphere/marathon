package mesosphere.marathon.state

import com.codahale.metrics.{ Histogram, Meter, MetricRegistry }
import com.codahale.metrics.MetricRegistry.name

trait StateMetrics {

  // metrics!
  protected def registry: MetricRegistry

  private[this] val readRequests: Meter = registry.meter(name(getClass, "read-requests"))
  private[this] val readRequestErrors: Meter = registry.meter(name(getClass, "read-request-errors"))
  private[this] val readRequestTime: Histogram = registry.histogram(name(getClass, "read-request-time"))

  private[this] val writeRequests: Meter = registry.meter(name(getClass, "write-requests"))
  private[this] val writeRequestErrors: Meter = registry.meter(name(getClass, "write-request-errors"))
  private[this] val writeRequestTime: Histogram = registry.histogram(name(getClass, "write-request-time"))

  protected[this] def timed[T](hist: Histogram, invocations: Meter, errors: Meter)(f: => T): T = {
    invocations.mark()
    try {
      val t0 = System.nanoTime()
      val result = f
      val t1 = System.nanoTime()
      hist.update((t1 - t0) / 1000000)
      result
    }
    catch { case t: Throwable => errors.mark(); throw t }
  }

  protected[this] def timedRead[T](f: => T): T = timed(readRequestTime, readRequests, readRequestErrors)(f)
  protected[this] def timedWrite[T](f: => T): T = timed(writeRequestTime, writeRequests, writeRequestErrors)(f)

}
