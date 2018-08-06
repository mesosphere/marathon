package mesosphere.marathon
package api

import com.typesafe.scalalogging.StrictLogging
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.servlet.{AsyncEvent, AsyncListener}
import javax.ws.rs.core.HttpHeaders
import mesosphere.marathon.metrics.{Counter, Metrics}
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.{HttpChannelState, _}
import HttpTransferMetricsHandler._
import mesosphere.marathon.metrics.deprecated.ServiceMetric

/* Container for HTTP Metrics
 */
trait HttpTransferMetrics {
  // Our GZIP handler only supports writing gzipped responses, not reading

  /**
    * Metric representing the total HTTP entity gzipped bytes written (does not include HTTP headers)
    */
  val oldGzippedBytesWrittenMetric: Counter
  val newResponsesSizeGzippedMetric: Counter

  /**
    * Metric representing the total HTTP entity bytes read (does not include HTTP headers)
    */
  val oldBytesReadMetric: Counter
  val newRequestsSizeMetric: Counter

  /**
    * Metric representing the total HTTP entity bytes written (does not include HTTP headers)
    */
  val oldBytesWrittenMetric: Counter
  val newResponsesSizeMetric: Counter
}

/**
  * Implementation class for HttpMetrics
  *
  * This class is structured and named this way for backwards compatibility, due to the fact that our metrics are
  * currently (and lamentably) tied to classes.
  */
class HTTPMetricsFilter(metrics: Metrics) extends HttpTransferMetrics {
  override val oldGzippedBytesWrittenMetric =
    metrics.deprecatedCounter(ServiceMetric, getClass, "gzippedBytesWritten")
  override val oldBytesReadMetric =
    metrics.deprecatedCounter(ServiceMetric, getClass, "bytesRead")
  override val oldBytesWrittenMetric =
    metrics.deprecatedCounter(ServiceMetric, getClass, "bytesWritten")
  override val newResponsesSizeGzippedMetric = metrics.counter(
    "http.responses.size.gzipped", DropwizardUnitOfMeasurement.Memory)
  override val newRequestsSizeMetric = metrics.counter(
    "http.requests.size", DropwizardUnitOfMeasurement.Memory)
  override val newResponsesSizeMetric = metrics.counter(
    "http.responses.size", DropwizardUnitOfMeasurement.Memory)
}

class HttpTransferMetricsHandler(httpMetrics: HttpTransferMetrics) extends AbstractHandler with StrictLogging {
  val metricsListener: AsyncListener = new AsyncListener() {
    def onError(event: javax.servlet.AsyncEvent): Unit = {}

    def onTimeout(event: javax.servlet.AsyncEvent): Unit = {}

    def onStartAsync(event: javax.servlet.AsyncEvent): Unit = {
      event.getAsyncContext.addListener(this)
    }

    override def onComplete(event: AsyncEvent): Unit = event match {
      case ace: AsyncContextEvent =>
        updateResponse(ace.getHttpChannelState.getBaseRequest)
      case _ =>
        logger.error(s"${event.getClass} wasn't an AsyncContextEvent (We should see this)")
    }
  }

  override def handle(path: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val state = baseRequest.getHttpChannelState

    if (state.isInitial) {
      if (state.isSuspended)
        state.addListener(metricsListener)
      else
        updateResponse(baseRequest)
    }
  }

  private def isGzip(contentEncodingHeader: String): Boolean =
    (contentEncodingHeader != null) && contentEncodingHeader.equalsIgnoreCase("gzip")

  protected def updateResponse(request: Request) =
    if (!isExcluded(request)) {

      val bytesRead = request.getHttpInput.getContentConsumed
      val bytesWritten = request.getResponse.getHttpOutput.getWritten

      if (bytesRead > 0) { // bytesRead can be -1 if no entity provided
        httpMetrics.oldBytesReadMetric.increment(bytesRead)
        httpMetrics.newRequestsSizeMetric.increment(bytesRead)
      }

      if (bytesWritten > 0) { // bytesWritten can be -1 if no entity returned
        httpMetrics.oldBytesWrittenMetric.increment(bytesWritten)
        httpMetrics.newResponsesSizeMetric.increment(bytesWritten)
        if (isGzip(request.getResponse.getHeader(HttpHeaders.CONTENT_ENCODING))) {
          httpMetrics.oldGzippedBytesWrittenMetric.increment(bytesWritten)
          httpMetrics.newResponsesSizeGzippedMetric.increment(bytesWritten)
        }
      }
    }
}

object HttpTransferMetricsHandler {
  val SkipMetricsKey = "mesosphere.marathon.api.IgnoreBytesTransferred"
  private[HttpTransferMetricsHandler] def isExcluded(request: Request) =
    request.getAttribute(SkipMetricsKey) match {
      case null => false
      case "yes" => true
      case other =>
        throw new IllegalStateException(s"Expected unset or 'yes'; got ${other}")
    }

  /**
    * Mechanism to indicate that we should not collect metrics for this HTTP request. This is useful in situations when
    * these metrics are tracked another way.
    */
  def exclude(request: ServletRequest) = {
    val state = request match {
      case r: Request => r.getHttpChannelState.getState
      case _ => throw new IllegalStateException("We should never get here")
    }

    state match {
      case HttpChannelState.State.COMPLETED | HttpChannelState.State.COMPLETING =>
        throw new IllegalStateException("Request was already completed. It is too late to call HTTPMetricsHandler.disclude")
      case _ =>
        request.setAttribute(SkipMetricsKey, "yes")
    }
  }
}
