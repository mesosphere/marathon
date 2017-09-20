package mesosphere.marathon
package api

import java.io.{ IOException, InputStream, OutputStream }
import java.net._
import javax.inject.Named
import javax.net.ssl._
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import akka.Done
import akka.http.scaladsl.model.StatusCodes._
import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.io.IO
import mesosphere.marathon.stream.Implicits._
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
  * Servlet filter that proxies requests to the leader if we are not the leader.
  */
class LeaderProxyFilter @Inject() (
    httpConf: HttpConf,
    electionService: ElectionService,
    @Named(ModuleNames.HOST_PORT) myHostPort: String,
    forwarder: RequestForwarder) extends Filter {

  import LeaderProxyFilter._

  private[this] val scheme = if (httpConf.disableHttp()) "https" else "http"

  @SuppressWarnings(Array("EmptyMethod"))
  override def init(filterConfig: FilterConfig): Unit = {}

  @SuppressWarnings(Array("EmptyMethod"))
  override def destroy(): Unit = {}

  private[this] def buildUrl(leaderData: String, request: HttpServletRequest): URL = {
    buildUrl(leaderData, request.getRequestURI, Option(request.getQueryString))
  }

  private[this] def buildUrl(
    leaderData: String,
    requestURI: String = "",
    queryStringOpt: Option[String] = None): URL =
    {
      queryStringOpt match {
        case Some(queryString) => new URL(s"$scheme://$leaderData$requestURI?$queryString")
        case None => new URL(s"$scheme://$leaderData$requestURI")
      }
    }

  @tailrec
  final def doFilter(
    rawRequest: ServletRequest,
    rawResponse: ServletResponse,
    chain: FilterChain): Unit = {

    def waitForConsistentLeadership(): Boolean = {
      var retries = 10
      var result = false
      do {
        val weAreLeader = electionService.isLeader
        val currentLeaderData = electionService.leaderHostPort

        if (weAreLeader || currentLeaderData.exists(_ != myHostPort)) {
          log.info("Leadership info is consistent again!")
          result = true
          retries = 0
        } else if (retries >= 0) {
          // as long as we are not flagged as elected yet, the leadership transition is still
          // taking place and we hold back any requests.
          log.info(s"Waiting for consistent leadership state. Are we leader?: $weAreLeader, leader: $currentLeaderData")
          sleep()
        } else {
          log.error(
            s"inconsistent leadership state, refusing request for ourselves at $myHostPort. " +
              s"Are we leader?: $weAreLeader, leader: $currentLeaderData")
        }

        retries -= 1
      } while (retries >= 0)

      result
    }

    (rawRequest, rawResponse) match {
      case (request: HttpServletRequest, response: HttpServletResponse) =>
        lazy val leaderDataOpt = electionService.leaderHostPort

        if (electionService.isLeader) {
          response.addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, buildUrl(myHostPort).toString)
          chain.doFilter(request, response)
        } else if (leaderDataOpt.forall(_ == myHostPort)) { // either not leader or ourselves
          log.info(
            "Do not proxy to myself. Waiting for consistent leadership state. " +
              s"Are we leader?: false, leader: $leaderDataOpt")
          if (waitForConsistentLeadership()) {
            doFilter(rawRequest, rawResponse, chain)
          } else {
            response.sendError(ServiceUnavailable.intValue, ERROR_STATUS_NO_CURRENT_LEADER)
          }
        } else {
          try {
            leaderDataOpt.foreach { leaderData =>
              val url = buildUrl(leaderData, request)
              forwarder.forward(url, request, response)
            }
          } catch {
            case NonFatal(e) =>
              throw new RuntimeException("while proxying", e)
          }
        }
      case _ =>
        throw new IllegalArgumentException(s"expected http request/response but got $rawRequest/$rawResponse")
    }
  }

  protected def sleep(): Unit = {
    Thread.sleep(250)
  }
}

object LeaderProxyFilter {
  private val log = LoggerFactory.getLogger(getClass.getName)

  val HEADER_MARATHON_LEADER: String = "X-Marathon-Leader"
  val ERROR_STATUS_NO_CURRENT_LEADER: String = "Could not determine the current leader"
}

/**
  * Forwards a HttpServletRequest to an URL.
  */
trait RequestForwarder {
  def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit
}

class JavaUrlConnectionRequestForwarder @Inject() (
  @Named(JavaUrlConnectionRequestForwarder.NAMED_LEADER_PROXY_SSL_CONTEXT) sslContext: SSLContext,
  leaderProxyConf: LeaderProxyConf,
  @Named(ModuleNames.HOST_PORT) myHostPort: String)
    extends RequestForwarder {

  import JavaUrlConnectionRequestForwarder._

  private[this] val viaValue: String = s"1.1 $myHostPort"

  private lazy val ignoreHostnameVerifier = new javax.net.ssl.HostnameVerifier {
    override def verify(hostname: String, sslSession: SSLSession): Boolean = true
  }

  override def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit = {

    def hasProxyLoop: Boolean = {
      Option(request.getHeaders(HEADER_VIA)).exists(_.seq.contains(viaValue))
    }

    def createAndConfigureConnection(url: URL): HttpURLConnection = {
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

    def copyRequestHeadersToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
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
        log.debug(s"addRequestProperty $name: $headerValue")
        leaderConnection.addRequestProperty(name, headerValue)
      }

      leaderConnection.addRequestProperty(HEADER_VIA, viaValue)
      val forwardedFor = Seq(
        Option(request.getHeader(HEADER_FORWARDED_FOR)),
        Option(request.getRemoteAddr)
      ).flatten.mkString(",")
      leaderConnection.addRequestProperty(HEADER_FORWARDED_FOR, forwardedFor)
    }

    def copyRequestBodyToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
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

    def copyRequestToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Try[Done] = Try {
      leaderConnection.setRequestMethod(request.getMethod)
      copyRequestHeadersToConnection(leaderConnection, request)
      copyRequestBodyToConnection(leaderConnection, request)
      Done
    }

    def cloneResponseStatusAndHeader(remote: HttpURLConnection, response: HttpServletResponse): Try[Done] = Try {
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

    def cloneResponseEntity(remote: HttpURLConnection, response: HttpServletResponse): Unit = {
      IO.using(response.getOutputStream) { output =>
        try {
          IO.using(remote.getInputStream) { connectionInput => copy(connectionInput, output) }
        } catch {
          case e: IOException =>
            log.debug("got exception response, this is maybe an error code", e)
            IO.using(remote.getErrorStream) { connectionError => copy(connectionError, output) }
        }
      }
    }

    log.info(s"Proxying request to ${request.getMethod} $url from $myHostPort")

    try {
      if (hasProxyLoop) {
        log.error("Prevent proxy cycle, rejecting request")
        response.sendError(BadGateway.intValue, ERROR_STATUS_LOOP)
      } else {
        val leaderConnection: HttpURLConnection = createAndConfigureConnection(url)
        try {
          copyRequestToConnection(leaderConnection, request) match {
            case Failure(ex: ConnectException) =>
              log.error(ERROR_STATUS_CONNECTION_REFUSED, ex)
              response.sendError(BadGateway.intValue, ERROR_STATUS_CONNECTION_REFUSED)
              return
            case Failure(ex: SocketTimeoutException) =>
              log.error(ERROR_STATUS_GATEWAY_TIMEOUT, ex)
              response.sendError(GatewayTimeout.intValue, ERROR_STATUS_GATEWAY_TIMEOUT)
              return
            case Failure(ex) =>
              log.error(ERROR_STATUS_BAD_CONNECTION, ex)
              response.sendError(InternalServerError.intValue)
              return
            case Success(_) => // ignore
          }
          copyConnectionResponse(response)(
            () => cloneResponseStatusAndHeader(leaderConnection, response),
            () => cloneResponseEntity(leaderConnection, response)
          )
        } finally {
          Try(leaderConnection.getInputStream.close())
          Try(leaderConnection.getErrorStream.close())
        }
      }
    } finally {
      Try(request.getInputStream.close())
      Try(response.getOutputStream.close())
    }

  }

  def copy(nullableIn: InputStream, nullableOut: OutputStream): Unit = {
    try {
      for {
        in <- Option(nullableIn)
        out <- Option(nullableOut)
      } IOUtils.copy(in, out)
    } catch {
      case e: UnknownServiceException =>
        log.warn("unexpected unknown service exception", e)
    }
  }
}

object JavaUrlConnectionRequestForwarder {
  private val log = LoggerFactory.getLogger(getClass)

  /** Header for proxy loop detection. Simply "Via" is ignored by the URL connection.*/
  val HEADER_VIA: String = "X-Marathon-Via"
  val ERROR_STATUS_LOOP: String = "Detected proxying loop."
  val ERROR_STATUS_CONNECTION_REFUSED: String = "Connection to leader refused."
  val ERROR_STATUS_GATEWAY_TIMEOUT: String = "Connection to leader timed out."
  val ERROR_STATUS_BAD_CONNECTION: String = "Failed to successfully establish a connection to the leader."

  val HEADER_FORWARDED_FOR: String = "X-Forwarded-For"
  final val NAMED_LEADER_PROXY_SSL_CONTEXT = "JavaUrlConnectionRequestForwarder.SSLContext"

  def copyConnectionResponse(response: HttpServletResponse)(
    forwardHeaders: () => Try[Done], forwardEntity: () => Unit): Unit = {

    forwardHeaders() match {
      case Failure(ex: SocketTimeoutException) =>
        log.error(ERROR_STATUS_GATEWAY_TIMEOUT, ex)
        response.sendError(GatewayTimeout.intValue, ERROR_STATUS_GATEWAY_TIMEOUT)
      case Failure(ex) =>
        // early detection of proxy failure, before we commit the status code to the response stream
        log.warn("failed to proxy response headers from leader", ex)
        response.sendError(BadGateway.intValue, ERROR_STATUS_BAD_CONNECTION)
      case Success(_) =>
        forwardEntity()
    }
  }
}
