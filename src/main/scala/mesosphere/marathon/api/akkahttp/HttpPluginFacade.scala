package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._
import mesosphere.marathon.plugin.http.{ HttpRequest => PluginRequest, HttpResponse => PluginResponse }

/**
  * HttpPluginFacade wraps around an akka http request / response and provides a plugin implementation.
  */
object HttpPluginFacade {

  /**
    * Provides a PluginRequest that uses an underlying akka http request.
    */
  def request(request: HttpRequest, remoteAddress: RemoteAddress, overridePath: Option[String] = None): PluginRequest = new PluginRequest {
    override def method: String = request.method.value
    override def cookie(name: String): Option[String] = request.cookies.find(_.name == name).map(_.value)
    override def remotePort: Int = remoteAddress.getPort()
    override def header(name: String): Seq[String] = request.headers.filter(_.name == name).map(_.value())
    override def queryParam(name: String): Seq[String] = request.uri.query().filter(_._1 == name).map(_._2)
    override def requestPath: String = overridePath.getOrElse(request.uri.path.toString())
    override def remoteAddr: String = remoteAddress.toString()
    override def localAddr: String = request.uri.authority.host.toString()
    override def localPort: Int = request.uri.authority.port
  }

  /**
    * Provides a PluginResponse for a function and yields a akka http response.
    */
  def response(fn: PluginResponse => Unit): HttpResponse = {
    val facade = new HttpResponseFacade
    fn(facade)
    facade.response
  }

  private[this] class HttpResponseFacade extends PluginResponse {
    private[this] var builder = HttpResponse()

    override def header(header: String, value: String): Unit = {
      builder = builder.copy(headers = RawHeader(header, value) +: builder.headers)
    }
    override def status(code: Int): Unit = {
      builder = builder.copy(status = code)
    }
    override def sendRedirect(url: String): Unit = {
      builder = builder.copy(
        status = StatusCodes.TemporaryRedirect,
        headers = Location(url) +: builder.headers
      )
    }
    override def cookie(name: String, value: String, maxAge: Int, secure: Boolean): Unit = {
      val cookie = `Set-Cookie`(HttpCookie(name, value, maxAge = Some(maxAge.toLong), secure = secure))
      builder = builder.copy(headers = cookie +: builder.headers)
    }
    override def body(mediaTypeString: String, bytes: Array[Byte]): Unit = {
      val contentType = MediaType.parse(mediaTypeString) match {
        case Right(mediaType) => ContentType(mediaType, () => HttpCharsets.`UTF-8`)
        case Left(errors) => throw new IllegalArgumentException(s"Could not parse $mediaTypeString. $errors")
      }
      builder = builder.copy(entity = HttpEntity(contentType, bytes))
    }
    def response: HttpResponse = builder
  }
}
