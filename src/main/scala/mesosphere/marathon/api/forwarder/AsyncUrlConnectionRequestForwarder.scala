package mesosphere.marathon
package api.forwarder

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpMethod, HttpMethods, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import java.net._
import javax.net.ssl._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import akka.Done
import akka.http.scaladsl.model.StatusCodes._
import com.google.common.io.Closeables
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.LeaderProxyFilter
import mesosphere.marathon.stream.Implicits._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Forwarder which uses Akka HTTP to proxy requests, and then
  */
class AsyncUrlConnectionRequestForwarder(
    sslContext: SSLContext,
    leaderProxyConf: LeaderProxyConf,
    myHostPort: String)(implicit executionContext: ExecutionContext, actorSystem: ActorSystem)
  extends RequestForwarder with StrictLogging {
  private implicit val mat = ActorMaterializer()

  import RequestForwarder._

  private[this] val viaValue: String = s"1.1 $myHostPort"

  private val sslConfig = AkkaSSLConfig().mapSettings { s =>
    if (leaderProxyConf.leaderProxySSLIgnoreHostname())
      s.withHostnameVerifierClass(classOf[IgnoringHostnameVerifier])
    else
      s
  }
  private val connectionContext = new HttpsConnectionContext(sslContext, sslConfig = Some(sslConfig))
  private val connectionSettings = ClientConnectionSettings(actorSystem).
    withIdleTimeout(leaderProxyConf.leaderProxyReadTimeout().millis).
    withConnectingTimeout(leaderProxyConf.leaderProxyConnectionTimeout().millis)
  private val poolSettings = ConnectionPoolSettings(actorSystem)
    .withConnectionSettings(connectionSettings)
    .withMaxRetries(0)

  class IgnoringHostnameVerifier extends javax.net.ssl.HostnameVerifier {
    override def verify(hostname: String, sslSession: SSLSession): Boolean = true
  }

  override def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit = {
    require(request.isAsyncSupported(), "ServletRequest does not support async mode")
    val asyncContext = request.startAsync()
    asyncContext.setTimeout(0L) // delegate timeout to stream

    def hasProxyLoop: Boolean = {
      Option(request.getHeaders(HEADER_VIA)).exists(_.seq.contains(viaValue))
    }

    def createAndConfigureConnection(url: URL, clientRequest: HttpServletRequest): Future[HttpResponse] = {
      val method = HttpMethods.getForKey(request.getMethod).getOrElse(HttpMethod.custom(request.getMethod))
      val uri = Uri(url.toString)
      val proxyHeaders = proxiedRequestHeaders(clientRequest)

      val contentType = Option(request.getContentType).map(ContentType.parse) match {
        case Some(Left(ex)) =>
          logger.error(s"Ignoring unparseable Content-Type: ${request.getContentType}", ex)
          ContentTypes.NoContentType
        case Some(Right(contentType)) =>
          contentType
        case None =>
          ContentTypes.NoContentType
      }

      val entity = clientRequest.getContentLengthLong match {
        case 0 =>
          HttpEntity.empty(contentType)
        case -1 =>
          HttpEntity.apply(contentType, Source.empty)
        case contentLength =>
          HttpEntity.apply(contentType, contentLength, ServletInputStreamSource.forAsyncContext(asyncContext))
      }

      val proxyRequest = HttpRequest(method, uri = uri, headers = proxyHeaders, entity = entity)
      Http().singleRequest(request = proxyRequest, connectionContext = connectionContext, settings = poolSettings)
    }

    def proxiedRequestHeaders(request: HttpServletRequest): Seq[HttpHeader] = {
      val headers = Seq.newBuilder[HttpHeader]
      // getHeaderNames() and getHeaders() are known to return null, see:
      //http://docs.oracle.com/javaee/6/api/javax/servlet/http/HttpServletRequest.html#getHeaders(java.lang.String)
      val names = Option(request.getHeaderNames).map(_.asScala).getOrElse(Nil)
      for {
        name <- names
        // Reverse proxies commonly filter these headers: connection, host.
        //
        // The connection header is removed since it may make sense to persist the connection
        // for further requests even if this single client will stop using it.
        if !name.equalsIgnoreCase("connection")

        // The host header is used to choose the correct virtual host and should be set to the hostname
        // of the URL for HTTP 1.1. Thus we do not preserve it, even though Marathon does not care.
        if !name.equalsIgnoreCase("host")

        // These headers cannot be set by Akka HTTP as raw headers
        if !name.equalsIgnoreCase("content-type")
        if !name.equalsIgnoreCase("content-length")
        if !name.equalsIgnoreCase("user-agent")

        headerValues <- Option(request.getHeaders(name))
        headerValue <- headerValues.seq
      } {
        logger.debug(s"add RawHeader $name: $headerValue")
        headers += RawHeader(name, headerValue)
      }

      headers += RawHeader(HEADER_VIA, viaValue)
      val forwardedFor = Seq(
        Option(request.getHeader(HEADER_FORWARDED_FOR)),
        Option(request.getRemoteAddr)
      ).flatten.mkString(",")
      headers += RawHeader(HEADER_FORWARDED_FOR, forwardedFor)
      Option(request.getHeader("User-Agent")).foreach { ua =>
        headers += akka.http.scaladsl.model.headers.`User-Agent`(ua)
      }
      headers.result()
    }

    def cloneResponseStatusAndHeader(remote: HttpResponse, response: HttpServletResponse): Unit = {
      response.setStatus(remote.status.intValue)

      remote.headers.foreach {
        case HttpHeader(name, value) =>
          // Akka HTTP does not preserve case for headers.
          if (name.equalsIgnoreCase("date"))
            response.setHeader("Date", value)
          else if (name.equalsIgnoreCase("vary"))
            response.setHeader("Vary", value)
          else if (name.equalsIgnoreCase(LeaderProxyFilter.HEADER_MARATHON_LEADER))
            response.addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, value)
          else
            response.addHeader(name, value)
      }
      response.addHeader(HEADER_VIA, viaValue)
      Done
    }

    logger.info(s"Proxying request to ${request.getMethod} $url from $myHostPort")

    val result: Future[Done] = try {
      if (hasProxyLoop) {
        logger.error("Prevent proxy cycle, rejecting request")
        response.sendError(BadGateway.intValue, ERROR_STATUS_LOOP)
        Future.successful(Done)
      } else {
        val leaderRequest = try {
          createAndConfigureConnection(url, request)
        } catch {
          case ex: Throwable =>
            Future.failed(ex)
        }

        leaderRequest.transform {
          case Failure(ex: akka.stream.StreamTcpException) =>
            /* Unfortunately, akka-http does not give us a different error message if the TCP connection is established,
             * but gives no response, or if the TCP connection is refused outright.
             *
             * So, we report BadGateway in either case.
             */
            logger.error(ERROR_STATUS_CONNECTION_REFUSED, ex)
            response.sendError(BadGateway.intValue, ERROR_STATUS_CONNECTION_REFUSED)
            Success(Done)
          case Failure(ex) =>
            logger.error(ERROR_STATUS_BAD_CONNECTION, ex)
            response.sendError(InternalServerError.intValue)
            Success(Done)
          case Success(proxyResponse) =>
            val outputSink = ServletOutputStreamSink.forAsyncContext(asyncContext)
            cloneResponseStatusAndHeader(proxyResponse, response)
            proxyResponse.entity.contentLengthOption.foreach { len =>
              response.setContentLength(len.toInt)
            }
            proxyResponse.entity.dataBytes.runWith(outputSink)
            Success(Done)
        }
      }
    } catch {
      case ex: Exception => Future.failed(ex)
    }

    result.onComplete {
      case _ =>
        Closeables.closeQuietly(request.getInputStream())
    }
    result.failed.foreach { e =>
      logger.error("Unhandled proxy exception", e)
      response.sendError(InternalServerError.intValue)
    }
  }
}
