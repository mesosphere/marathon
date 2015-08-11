package mesosphere.marathon.api

import javax.servlet.http.{ HttpServlet, HttpServletRequest, HttpServletResponse }

import mesosphere.marathon.io.IO
import org.apache.log4j.Logger

class WebJarServlet extends HttpServlet {

  private[this] val log = Logger.getLogger(getClass)

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    //extract request data
    val jar = req.getServletPath // e.g. /ui
    var resource = req.getPathInfo // e.g. /fonts/icon.gif
    if (resource.endsWith("/")) resource = resource + "index.html" // welcome file
    val file = resource.split("/").last //e.g. icon.gif
    val mediaType = file.split("\\.").lastOption.getOrElse("") //e.g. gif
    val mime = Option(getServletContext.getMimeType(file)).getOrElse(mimeType(mediaType)) //e.g plain/text
    val resourceURI = s"/META-INF/resources/webjars$jar$resource"

    //log request data, since the names are not very intuitive
    if (log.isDebugEnabled) {
      log.debug(
        s"""
         |pathinfo: ${req.getPathInfo}
         |context: ${req.getContextPath}
         |servlet: ${req.getServletPath}
         |path: ${req.getPathTranslated}
         |uri: ${req.getRequestURI}
         |jar: $jar
         |resource: $resource
         |file: $file
         |mime: $mime
         |resourceURI: $resourceURI
       """.stripMargin)
    }

    def sendResource(): Unit = {
      //scalastyle:off magic.number
      IO.withResource(resourceURI) { stream =>
        resp.setHeader("Content-Type", mime)
        resp.setContentLength(stream.available())
        resp.setStatus(200)
        IO.transfer(stream, resp.getOutputStream)
      } getOrElse {
        resp.sendError(404)
      }
    }

    //special rule for accessing root -> redirect to ui main page
    if (req.getRequestURI == "/") resp.sendRedirect("/ui/")
    //if a directory is requested, redirect to trailing slash
    else if (!file.contains(".")) resp.sendRedirect(req.getRequestURI + "/") //request /ui -> /ui/
    //if we come here, it must be a resource
    else sendResource()
  }

  private[this] def mimeType(mediaType: String): String = {
    mediaType.toLowerCase match {
      case "eot" => "application/vnd.ms-fontobject"
      case "svg" => "image/svg+xml"
      case "ttf" => "application/font-ttf"
      case _     => "application/octet-stream"
    }
  }
}
