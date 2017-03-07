package mesosphere.marathon
package metrics

import javax.ws.rs.core.Response

import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{ Around, Aspect }

private object ServletTracing {
  private[metrics] val `1xx` = Kamon.metrics.counter("org.eclipse.jetty.ServletContextHandler.1xx-responses")
  private[metrics] val `2xx` = Kamon.metrics.counter("org.eclipse.jetty.ServletContextHandler.2xx-responses")
  private[metrics] val `3xx` = Kamon.metrics.counter("org.eclipse.jetty.ServletContextHandler.3xx-responses")
  private[metrics] val `4xx` = Kamon.metrics.counter("org.eclipse.jetty.ServletContextHandler.4xx-responses")
  private[metrics] val `5xx` = Kamon.metrics.counter("org.eclipse.jetty.ServletContextHandler.5xx-responses")
}

/** Automatically time all servlet endpoints */
@Aspect
private class ServletTracing extends StrictLogging {
  @Around("execution(@javax.ws.rs.Path javax.ws.rs.core.Response *(..))")
  @SuppressWarnings(Array("MethodReturningAny"))
  def track(pjp: ProceedingJoinPoint): AnyRef = {
    val timer = Metrics.timer(ApiMetric, pjp.getSignature.getDeclaringType, pjp.getSignature.getName)
    val result = timer.blocking(pjp.proceed)
    result match {
      case r: Response if r.getStatus < 200 => ServletTracing.`1xx`.increment()
      case r: Response if r.getStatus < 300 => ServletTracing.`2xx`.increment()
      case r: Response if r.getStatus < 400 => ServletTracing.`3xx`.increment()
      case r: Response if r.getStatus < 500 => ServletTracing.`4xx`.increment()
      case _ => ServletTracing.`5xx`.increment()
    }
    result
  }
}

