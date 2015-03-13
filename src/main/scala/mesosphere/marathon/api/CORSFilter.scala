package mesosphere.marathon.api

import javax.inject.{ Inject }
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import mesosphere.marathon.{ MarathonConf }

import scala.collection.JavaConverters._

class CORSFilter @Inject() (config: MarathonConf) extends Filter {
  // Map access_control_allow_origin flag into separate headers
  lazy val origins: Seq[String] = config.accessControlAllowOrigin()
    .split(",")
    .map(_.trim)

  override def init(filterConfig: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {

    response match {
      case httpResponse: HttpServletResponse if config.accessControlAllowOrigin.isSupplied =>
        val httpRequest = request.asInstanceOf[HttpServletRequest]

        origins.foreach { origin =>
          httpResponse.setHeader("Access-Control-Allow-Origin", origin)
        }

        // Add all headers from request as accepted headers
        val accessControlRequestHeaders =
          httpRequest.getHeaders("Access-Control-Request-Headers")
            .asScala
            .flatMap(_.split(","))

        httpResponse.setHeader("Access-Control-Allow-Headers", accessControlRequestHeaders.mkString(", "))

        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        httpResponse.setHeader("Access-Control-Max-Age", "86400")

      case _ => // Ignore other responses

    }
    chain.doFilter(request, response)
  }

  override def destroy(): Unit = {}
}
