package mesosphere.chaos.http

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse, HttpServlet }

/**
  * An HTTP servlets which outputs a {@code text/plain} {@code "pong"} response.
  *
  * PingServlet from com.codahale.metrics, with CORS header added.
  */

class PingServlet extends HttpServlet {

  private final val CONTENT_TYPE = "text/plain"
  private final val CONTENT = "pong"
  private final val CACHE_CONTROL = "Cache-Control"
  private final val NO_CACHE = "must-revalidate,no-cache,no-store"
  private final val ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin"
  private final val ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"
  private final val TRUE = "true"
  private final val ORIGIN = "Origin"

  protected override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.setStatus(HttpServletResponse.SC_OK)
    resp.setHeader(CACHE_CONTROL, NO_CACHE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, TRUE)
    resp.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, req.getHeader(ORIGIN))
    resp.setContentType(CONTENT_TYPE)

    val writer = resp.getWriter
    try {
      writer.println(CONTENT)
    } finally {
      writer.close()
    }
  }
}
