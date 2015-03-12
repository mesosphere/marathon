package mesosphere.marathon.api

import javax.inject.{ Inject }
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import mesosphere.marathon.{ MarathonConf }

import scala.collection.JavaConverters._

class CORSFilter @Inject() (config: MarathonConf) extends Filter {
  override def init(filterConfig: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {

    response match {
      case httpResponse: HttpServletResponse if config.accessControlAllowOrigin.isSupplied =>
        val httpRequest = request.asInstanceOf[HttpServletRequest]
        val static = ("Accept,Content-Type,Referer,Origin,Connection,Cache-Control,Access-Control-Request-Headers," +
          "Pragma,Access-Control-Request-Method,Accept-Language,Accept-Encoding,User-Agent,Host,Accept-Language," +
          "Accept-Encoding,Content-Length,Origin,X-DevTools-Emulate-Network-Conditions-Client-Id").split(",")
        val staticSet = static.map(_.toLowerCase).toSet
        val control = httpRequest.getHeaders("Access-Control-Request-Headers").asScala
          .flatMap(_.split(","))
          .filter(hd => staticSet.contains(hd.toLowerCase))

        httpResponse.setHeader("Access-Control-Allow-Origin", config.accessControlAllowOrigin())
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        httpResponse.setHeader("Access-Control-Max-Age", "86400")
        httpResponse.setHeader("Access-Control-Allow-Headers", (static ++ control).toList.distinct.mkString(", "))
      case _ => // ignore other responses

    }
    chain.doFilter(request, response)
  }

  override def destroy(): Unit = {}
}
