package mesosphere.marathon.api

import java.util
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletRequestWrapper }

import scala.collection.JavaConverters._

class SetRequestDefaultsFilter extends Filter {

  //scalastyle:off null

  /**
    * Wrap original request and provide a consistent representation.
    * @param request the original request.
    */
  class RequestWithDefaults(request: HttpServletRequest) extends HttpServletRequestWrapper(request) {
    val headers = {
      var headerMap = request.getHeaderNames.asScala.map(k => k -> request.getHeaders(k)).toMap
      if (!headerMap.contains("Content-Type")) headerMap += "Content-Type" -> enum("application/json")
      if (!headerMap.contains("Accept")) headerMap += "Accept" -> enum("application/json")
      headerMap
    }.withDefaultValue(emptyEnum)

    override val getHeaderNames: util.Enumeration[String] = new util.Vector(headers.keySet.asJava).elements()

    override def getHeader(name: String): String = {
      headers.get(name).map(e => if (e.hasMoreElements) e.nextElement() else null).orNull
    }

    override def getHeaders(name: String): util.Enumeration[String] = headers(name)
  }

  override def doFilter(rawRequest: ServletRequest, rawResponse: ServletResponse, chain: FilterChain): Unit = {
    rawRequest match {
      case request: HttpServletRequest => chain.doFilter(new RequestWithDefaults(request), rawResponse)
      case _ =>
        throw new IllegalArgumentException(s"expected http request but got $rawRequest")
    }
  }

  override def init(filterConfig: FilterConfig): Unit = {}
  override def destroy(): Unit = {}
  private[this] def enum(values: String*) = new util.Vector(values.asJava).elements()
  private[this] val emptyEnum = new util.Vector[String](0).elements()
}
