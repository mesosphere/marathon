package mesosphere.marathon
package api

import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import mesosphere.marathon.metrics.Metrics
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

/**
  * Counters of HTTP status codes.
  *
  * These metrics are meant to be used with deprecated metrics only.
  */
class HttpResponseMetricsHandler(metrics: Metrics) extends AbstractHandler {
  private[this] val `1xx` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.1xx-responses")
  private[this] val `2xx` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.2xx-responses")
  private[this] val `3xx` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.3xx-responses")
  private[this] val `4xx` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.4xx-responses")
  private[this] val `5xx` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.5xx-responses")
  private[this] val `api-errors` = metrics.deprecatedCounter("org.eclipse.jetty.ServletContextHandler.api-errors")

  override def handle(target: String, baseRequest: Request,
    request: HttpServletRequest, response: HttpServletResponse): Unit = {
    response.getStatus match {
      case status if status < 200 => `1xx`.increment()
      case status if status < 300 => `2xx`.increment()
      case status if status < 400 => `3xx`.increment()
      case status if status < 500 => `4xx`.increment()
      // Since it can happen for marathon to return HTTP 503 e.g. while leader is
      // reelected, we implement an extra metric `api-error` which gathers all
      // 5xx status codes except for HTTP 503.
      case status =>
        if (status != 503)
          `api-errors`.increment()
        `5xx`.increment()
    }
  }
}
