package mesosphere.marathon
package api

import java.net._
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.api.forwarder.RequestForwarder

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
  * Servlet filter that proxies requests to the leader if we are not the leader.
  */
class LeaderProxyFilter(
    disableHttp: Boolean,
    electionService: ElectionService,
    myHostPort: String,
    forwarder: RequestForwarder,
    proxyEvents: Boolean
) extends Filter with StrictLogging {

  import LeaderProxyFilter._

  private[this] val scheme = if (disableHttp) "https" else "http"

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
          logger.info("Leadership info is consistent again!")
          result = true
          retries = 0
        } else if (retries >= 0) {
          // as long as we are not flagged as elected yet, the leadership transition is still
          // taking place and we hold back any requests.
          logger.info(s"Waiting for consistent leadership state. Are we leader?: $weAreLeader, leader: $currentLeaderData")
          sleep()
        } else {
          logger.error(
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
          logger.info(
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
              if (shouldBeRedirectedToLeader(request)) {
                response.sendRedirect(url.toString)
              } else {
                forwarder.forward(url, request, response)
              }
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

  /**
    * Returns true if this request is a /v2/events request, and proxy events (a deprecated feature) is disabled.
    */
  private def shouldBeRedirectedToLeader(request: HttpServletRequest): Boolean = {
    request.getRequestURI.startsWith(HttpBindings.EventsPath) && (proxyEvents == false)
  }

  protected def sleep(): Unit = {
    Thread.sleep(250)
  }
}

object LeaderProxyFilter {
  val HEADER_MARATHON_LEADER: String = "X-Marathon-Leader"
  val ERROR_STATUS_NO_CURRENT_LEADER: String = "Could not determine the current leader"
}
