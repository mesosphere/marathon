package mesosphere.chaos.http

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse, HttpServlet }
import javax.inject.Inject
import mesosphere.chaos.ServiceStatus

/**
  * An HTTP servlets which allows turning a service off without killing it.
  * Thus it can still finish serving requests but it can be prevented that new
  * requests access it. It returns a {@code text/plain} response indicating if
  * the service is turned on or off.
  *
  */

class ServiceStatusServlet @Inject() (val status: ServiceStatus)
  extends HttpServlet {

  private final val CONTENT_TYPE = "text/plain"
  private final val CACHE_CONTROL = "Cache-Control"
  private final val NO_CACHE = "must-revalidate,no-cache,no-store"
  private final val ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin"
  private final val ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"
  private final val TRUE = "true"
  private final val ORIGIN = "Origin"

  protected override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
    resp.setStatus(HttpServletResponse.SC_OK)
    resp.setHeader(CACHE_CONTROL, NO_CACHE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, TRUE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, req.getHeader(ORIGIN))
    resp.setContentType(CONTENT_TYPE)

    val writer = resp.getWriter
    try {
      val response = status.isOn.get() match {
        case true => "on"
        case false => "off"
      }
      writer.println(response)
    } finally {
      writer.close()
    }
  }

  protected override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val pathInfo = req.getPathInfo()
    if (pathInfo.endsWith("/on")) {
      status.isOn.set(true)
    } else if (pathInfo.endsWith("/off")) {
      status.isOn.set(false)
    } else {
      resp.sendError(
        HttpServletResponse.SC_NOT_FOUND,
        "Invalid URL")
      return
    }
    resp.setHeader(CACHE_CONTROL, NO_CACHE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, TRUE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, req.getHeader(ORIGIN))
    resp.setContentType(CONTENT_TYPE)
    resp.setStatus(HttpServletResponse.SC_NO_CONTENT)
  }
}
