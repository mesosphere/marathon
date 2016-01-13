package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics.{ Histogram, Meter }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }

import scala.concurrent.Future
import scala.util.control.NonFatal

object StateMetrics {
  private[state] class MetricTemplate(
      metrics: Metrics, prefix: String, metricsClass: Class[_],
      nanoTime: () => Long = () => System.nanoTime) {
    def timedFuture[T](f: => Future[T]): Future[T] = {
      requestMeter.mark()
      val t0 = nanoTime()
      val result: Future[T] =
        try f
        catch {
          case NonFatal(t) =>
            // if the function did not even manage to return the Future
            val t1 = nanoTime()
            durationHistogram.update((t1 - t0) / 1000000)
            errorMeter.mark()
            throw t
        }

      import mesosphere.util.CallerThreadExecutionContext.callerThreadExecutionContext
      result.onComplete { _ =>
        val t1 = nanoTime()
        durationHistogram.update((t1 - t0) / 1000000)
      }
      result.onFailure {
        case _ => errorMeter.mark()
      }

      result
    }

    val requestsMeterName = metricName(s"$prefix-requests")
    val errorMeterName = metricName(s"$prefix-request-errors")
    val durationHistogramName = metricName(s"$prefix-request-time")

    private[this] val requestMeter: Meter = metrics.meter(requestsMeterName)
    private[this] val errorMeter: Meter = metrics.meter(errorMeterName)
    private[this] val durationHistogram: Histogram = metrics.histogram(durationHistogramName)

    private[this] def metricName(name: String): String = metrics.name(MetricPrefixes.SERVICE, metricsClass, name)
  }

}

trait StateMetrics {

  protected val metrics: Metrics

  protected val readMetrics = new StateMetrics.MetricTemplate(metrics, "read", getClass, nanoTime)
  protected val writeMetrics = new StateMetrics.MetricTemplate(metrics, "write", getClass, nanoTime)

  protected[this] def timedRead[T](f: => Future[T]): Future[T] = readMetrics.timedFuture(f)

  protected[this] def timedWrite[T](f: => Future[T]): Future[T] = writeMetrics.timedFuture(f)

  protected def nanoTime(): Long = System.nanoTime()
}
