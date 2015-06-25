package mesosphere.marathon.state

import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.metrics.Metrics.{ Histogram, Meter }

trait StateMetrics {

  // metrics!
  protected val metrics: Metrics

  private[this] def prefix: String = MetricPrefixes.SERVICE

  private[this] val readRequests: Meter = metrics.meter(metrics.name(prefix, getClass, "read-requests"))
  private[this] val readRequestErrors: Meter = metrics.meter(metrics.name(prefix, getClass, "read-request-errors"))
  private[this] val readRequestTime: Histogram = metrics.histogram(metrics.name(prefix, getClass, "read-request-time"))

  private[this] val writeRequests: Meter = metrics.meter(metrics.name(prefix, getClass, "write-requests"))
  private[this] val writeRequestErrors: Meter = metrics.meter(metrics.name(prefix, getClass, "write-request-errors"))
  private[this] val writeRequestTime: Histogram =
    metrics.histogram(metrics.name(prefix, getClass, "write-request-time"))

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
