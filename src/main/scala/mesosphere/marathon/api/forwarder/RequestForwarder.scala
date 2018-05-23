package mesosphere.marathon
package api.forwarder

import java.net.URL
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

/**
  * Forwards a HttpServletRequest to an URL.
  */
trait RequestForwarder {
  def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit
}

object RequestForwarder {

  /** Header for proxy loop detection. Simply "Via" is ignored by the URL connection.*/
  val HEADER_VIA: String = "X-Marathon-Via"
  val ERROR_STATUS_LOOP: String = "Detected proxying loop."
  val ERROR_STATUS_CONNECTION_REFUSED: String = "Connection to leader refused."
  val ERROR_STATUS_GATEWAY_TIMEOUT: String = "Connection to leader timed out."
  val ERROR_STATUS_BAD_CONNECTION: String = "Failed to successfully establish a connection to the leader."

  val HEADER_FORWARDED_FOR: String = "X-Forwarded-For"
}
