package mesosphere.marathon.api

import java.util.concurrent.Semaphore
import javax.servlet._
import javax.servlet.http.HttpServletResponse

/**
  * Limit the number of concurrent http requests.
  * @param concurrentRequests the maximum number of concurrent requests.
  */
class LimitConcurrentRequestsFilter(concurrentRequests: Int) extends Filter {

  private[this] val semaphore = new Semaphore(concurrentRequests)

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    if (semaphore.tryAcquire()) {
      try { chain.doFilter(request, response) }
      finally { semaphore.release() }
    }
    else {
      response match {
        //scalastyle:off magic.number
        case r: HttpServletResponse => r.sendError(503, s"Too many concurrent requests! Allowed: $concurrentRequests.")
        case r: ServletResponse     => throw new IllegalArgumentException(s"Expected http response but got $response")
      }
    }
  }

  override def init(filterConfig: FilterConfig): Unit = {}
  override def destroy(): Unit = {}
}
