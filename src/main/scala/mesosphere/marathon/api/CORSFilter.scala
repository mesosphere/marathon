package mesosphere.marathon
package api

import javax.inject.Inject
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import scala.jdk.CollectionConverters._

class CORSFilter @Inject() (config: MarathonConf) extends Filter {

  lazy val maybeOrigins: Option[Seq[String]] = config.accessControlAllowOrigin.toOption

  override def init(filterConfig: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {

    response match {
      case httpResponse: HttpServletResponse if maybeOrigins.isDefined =>
        request match {
          case httpRequest: HttpServletRequest =>
            maybeOrigins.foreach { origins =>
              origins.foreach { origin =>
                httpResponse.setHeader("Access-Control-Allow-Origin", origin)
              }
            }

            // Add all headers from request as accepted headers
            // Unclear why `toTraversableOnce` isn't being applied implicitly here.
            val accessControlRequestHeaders =
              httpRequest.getHeaders("Access-Control-Request-Headers").asScala.flatMap(_.split(","))

            httpResponse.setHeader("Access-Control-Allow-Headers", accessControlRequestHeaders.mkString(", "))

            httpResponse.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            httpResponse.setHeader("Access-Control-Max-Age", "86400")
          case _ =>
        }
      case _ => // Ignore other responses
    }
    chain.doFilter(request, response)
  }

  override def destroy(): Unit = {}
}
