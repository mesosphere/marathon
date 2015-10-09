package mesosphere.marathon.api

import java.util.concurrent.Semaphore
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

/**
  * Limit the number of concurrent http requests if the concurrent number is set.
  * @param concurrentOption the optional maximum number of concurrent requests.
  */
class LimitConcurrentRequestsFilter(concurrentOption: Option[Int]) extends Filter {

  val concurrent = concurrentOption.getOrElse(0)
  val semaphore = new Semaphore(concurrent)
  val filterFunction = concurrentOption.map(_ => withSemaphore _).getOrElse(pass _)

  def withSemaphore(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    request match {
      case r: HttpServletRequest if r.getMethod != "GET" =>
        // Only throttle non-GET
        throttle(request, response, chain)
      case _ =>
        pass(request, response, chain)
    }
  }

  def pass(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    chain.doFilter(request, response)
  }

  def throttle(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    if (semaphore.tryAcquire()) {
      try { pass(request, response, chain) }
      finally { semaphore.release() }
    }
    else {
      response match {
        //scalastyle:off magic.number
        case r: HttpServletResponse => r.sendError(503, s"Too many concurrent requests! Allowed: $concurrent.")
        case r: ServletResponse     => throw new IllegalArgumentException(s"Expected http response but got $response")
      }
    }
  }

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    filterFunction(request, response, chain)
  }

  override def init(filterConfig: FilterConfig): Unit = {}
  override def destroy(): Unit = {}
}
