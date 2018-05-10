package mesosphere.marathon
package api.forwarder

import java.io.{IOException, InputStream, OutputStream}
import java.net.{ConnectException, HttpURLConnection, SocketTimeoutException, URL, URLConnection, UnknownServiceException}
import javax.net.ssl._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import akka.Done
import akka.http.scaladsl.model.StatusCodes._
import com.google.common.io.Closeables
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.io.IO
import mesosphere.marathon.stream.Implicits._

import scala.util.{Failure, Success, Try}

class JavaUrlConnectionRequestForwarder(
    sslContext: SSLContext,
    leaderProxyConf: LeaderProxyConf,
    myHostPort: String)
  extends RequestForwarder with StrictLogging {

  import JavaUrlConnectionRequestForwarder.copyConnectionResponse
  import RequestForwarder._

  private[this] val viaValue: String = s"1.1 $myHostPort"

  private lazy val ignoreHostnameVerifier = new javax.net.ssl.HostnameVerifier {
    override def verify(hostname: String, sslSession: SSLSession): Boolean = true
  }

  private def copyRequestBodyToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
    request.getMethod match {
      case "GET" | "HEAD" | "DELETE" =>
        leaderConnection.setDoOutput(false)
      case _ =>
        leaderConnection.setDoOutput(true)

        IO.using(request.getInputStream) { requestInput =>
          IO.using(leaderConnection.getOutputStream) { proxyOutputStream =>
            copy(request.getInputStream, proxyOutputStream)
          }
        }
    }
  }

  private def createAndConfigureConnection(url: URL): HttpURLConnection = {
    val connection = url.openConnection() match {
      case httpsConnection: HttpsURLConnection =>
        httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory)

        if (leaderProxyConf.leaderProxySSLIgnoreHostname()) {
          httpsConnection.setHostnameVerifier(ignoreHostnameVerifier)
        }

        httpsConnection
      case httpConnection: HttpURLConnection =>
        httpConnection
      case connection: URLConnection =>
        throw new scala.RuntimeException(s"unexpected connection type: ${connection.getClass}")
    }

    connection.setConnectTimeout(leaderProxyConf.leaderProxyConnectionTimeout())
    connection.setReadTimeout(leaderProxyConf.leaderProxyReadTimeout())
    connection.setInstanceFollowRedirects(false)

    connection
  }

  private def copyRequestHeadersToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
    // getHeaderNames() and getHeaders() are known to return null, see:
    //http://docs.oracle.com/javaee/6/api/javax/servlet/http/HttpServletRequest.html#getHeaders(java.lang.String)
    val names = Option(request.getHeaderNames).map(_.seq).getOrElse(Nil)
    for {
      name <- names
      // Reverse proxies commonly filter these headers: connection, host.
      //
      // The connection header is removed since it may make sense to persist the connection
      // for further requests even if this single client will stop using it.
      //
      // The host header is used to choose the correct virtual host and should be set to the hostname
      // of the URL for HTTP 1.1. Thus we do not preserve it, even though Marathon does not care.
      if !name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("connection")
      headerValues <- Option(request.getHeaders(name))
      headerValue <- headerValues.seq
    } {
      logger.debug(s"addRequestProperty $name: $headerValue")
      leaderConnection.addRequestProperty(name, headerValue)
    }

    leaderConnection.addRequestProperty(HEADER_VIA, viaValue)
    val forwardedFor = Seq(
      Option(request.getHeader(HEADER_FORWARDED_FOR)),
      Option(request.getRemoteAddr)
    ).flatten.mkString(",")
    leaderConnection.addRequestProperty(HEADER_FORWARDED_FOR, forwardedFor)
  }

  private def copyRequestToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Try[Done] = Try {
    leaderConnection.setRequestMethod(request.getMethod)
    copyRequestHeadersToConnection(leaderConnection, request)
    copyRequestBodyToConnection(leaderConnection, request)
    Done
  }

  private def cloneResponseStatusAndHeader(remote: HttpURLConnection, response: HttpServletResponse): Try[Done] = Try {
    val status = remote.getResponseCode
    response.setStatus(status)

    Option(remote.getHeaderFields).foreach { fields =>
      // headers and values can both be null :(
      fields.foreach {
        case (n, v) =>
          (Option(n), Option(v)) match {
            case (Some(name), Some(values)) =>
              values.foreach(value =>
                response.addHeader(name, value)
              )
            case _ => // ignore
          }
      }
    }
    response.addHeader(HEADER_VIA, viaValue)
    Done
  }

  private def cloneResponseEntity(remote: HttpURLConnection, response: HttpServletResponse): Unit = {
    IO.using(response.getOutputStream) { output =>
      try {
        IO.using(remote.getInputStream) { connectionInput => copy(connectionInput, output) }
      } catch {
        case e: IOException =>
          logger.debug("got exception response, this is maybe an error code", e)
          IO.using(remote.getErrorStream) { connectionError => copy(connectionError, output) }
      }
    }
  }

  override def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit = {

    logger.info(s"Proxying request to ${request.getMethod} $url from $myHostPort")

    try {
      val hasProxyLoop: Boolean = Option(request.getHeaders(HEADER_VIA)).exists(_.seq.contains(viaValue))

      if (hasProxyLoop) {
        logger.error("Prevent proxy cycle, rejecting request")
        response.sendError(BadGateway.intValue, ERROR_STATUS_LOOP)
      } else {
        val leaderConnection: HttpURLConnection = createAndConfigureConnection(url)
        try {
          copyRequestToConnection(leaderConnection, request) match {
            case Failure(ex: ConnectException) =>
              logger.error(ERROR_STATUS_CONNECTION_REFUSED, ex)
              response.sendError(BadGateway.intValue, ERROR_STATUS_CONNECTION_REFUSED)
            case Failure(ex: SocketTimeoutException) =>
              logger.error(ERROR_STATUS_GATEWAY_TIMEOUT, ex)
              response.sendError(GatewayTimeout.intValue, ERROR_STATUS_GATEWAY_TIMEOUT)
            case Failure(ex) =>
              logger.error(ERROR_STATUS_BAD_CONNECTION, ex)
              response.sendError(InternalServerError.intValue)
            case Success(_) => // ignore
          }
          copyConnectionResponse(response)(
            () => cloneResponseStatusAndHeader(leaderConnection, response),
            () => cloneResponseEntity(leaderConnection, response)
          )
        } finally {
          Closeables.closeQuietly(leaderConnection.getInputStream())
          Closeables.closeQuietly(leaderConnection.getErrorStream())
        }
      }
    } finally {
      Closeables.closeQuietly(request.getInputStream())
      Closeables.close(response.getOutputStream(), true)
    }
  }

  private def copy(nullableIn: InputStream, nullableOut: OutputStream): Unit = {
    try {
      // Note: This method blocks. That means it never returns if the request is for an SSE stream.
      IO.transfer(Option(nullableIn), Option(nullableOut))
    } catch {
      case e: UnknownServiceException =>
        logger.warn("unexpected unknown service exception", e)
    }
  }
}

object JavaUrlConnectionRequestForwarder extends StrictLogging {

  import RequestForwarder.{ERROR_STATUS_GATEWAY_TIMEOUT, ERROR_STATUS_BAD_CONNECTION}
  def copyConnectionResponse(response: HttpServletResponse)(
    forwardHeaders: () => Try[Done], forwardEntity: () => Unit): Unit = {

    forwardHeaders() match {
      case Failure(ex: SocketTimeoutException) =>
        logger.error(ERROR_STATUS_GATEWAY_TIMEOUT, ex)
        response.sendError(GatewayTimeout.intValue, ERROR_STATUS_GATEWAY_TIMEOUT)
      case Failure(ex) =>
        // early detection of proxy failure, before we commit the status code to the response stream
        logger.warn("failed to proxy response headers from leader", ex)
        response.sendError(BadGateway.intValue, ERROR_STATUS_BAD_CONNECTION)
      case Success(_) =>
        forwardEntity()
    }
  }
}
