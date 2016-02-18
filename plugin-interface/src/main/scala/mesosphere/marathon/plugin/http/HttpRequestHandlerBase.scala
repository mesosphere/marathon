package mesosphere.marathon.plugin.http

import java.net.{ URL, URLConnection }
import com.google.common.io.Resources

/**
  * The HttpRequestHandlerBase extends the HttpRequestHandler by
  * providing functionality to serve content packaged as resource.
  */
//scalastyle:off magic.number
abstract class HttpRequestHandlerBase extends HttpRequestHandler {

  protected[this] def serveResource(path: String, response: HttpResponse): Unit = {
    val content = withResource(path) { url =>
      response.body(mediaMime(url), Resources.toByteArray(url))
      response.status(200)
    }
    content.getOrElse(response.status(404))
  }

  protected[this] def withResource[T](path: String)(fn: URL => T): Option[T] = {
    Option(getClass.getResource(path)).map(fn)
  }

  protected[this] def mediaMime(url: URL): String = {
    url.getPath.split("\\.").lastOption.flatMap(wellKnownMimes.get)
      .orElse(Option(URLConnection.guessContentTypeFromName(url.toString)))
      .getOrElse("application/octet-stream")
  }

  protected[this] val wellKnownMimes = Map (
    "css" -> "text/css",
    "js" -> "application/javascript",
    "eot" -> "application/vnd.ms-fontobject",
    "svg" -> "image/svg+xml",
    "ttf" -> "application/font-ttf"
  )
}
