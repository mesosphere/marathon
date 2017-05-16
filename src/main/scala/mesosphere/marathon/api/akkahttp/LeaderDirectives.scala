package mesosphere.marathon
package api.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.Directives.{ extractRequest, pass, reject }
import akka.http.scaladsl.server._
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Headers._
import mesosphere.marathon.core.election.ElectionService

import scala.util.matching.Regex
import scala.util.{ Failure, Success }

trait LeaderDirectives extends StrictLogging {
  import LeaderDirectives._

  /**
    * Directive which, given an election service, rejects with NoLeaderRejection if not currently leader.
    *
    * The HTTP service's rejection handler should handle proxying via the handleNonLeader partialFunction below
    */
  def asLeader(electionService: ElectionService): Directive0 = {
    extractRequest.flatMap { request =>
      if (electionService.isLeader) {
        pass
      } else if (request.header[`X-Marathon-Via`].exists(_.via == electionService.localHostPort)) {
        reject(ProxyLoop)
      } else electionService.leaderHostPort match {
        case Some(host) => reject(ProxyToLeader(request, electionService.localHostPort, host))
        case None => reject(NoLeader)
      }
    }
  }
}

object LeaderDirectives extends StrictLogging {

  import akka.http.scaladsl.server.Directives._

  val HostPort: Regex = """^(.*):(\d+)$""".r
  val FilterHeaders: Set[String] = Set("Host", "Connection", "Timeout-Access")

  private[LeaderDirectives] case class ProxyToLeader(request: HttpRequest, localHostPort: String, leaderHost: String) extends Rejection
  private[LeaderDirectives] case object NoLeader extends Rejection
  private[LeaderDirectives] case object ProxyLoop extends Rejection

  def handleNonLeader(implicit actorSystem: ActorSystem, materializer: Materializer): PartialFunction[Rejection, Route] = {
    case ProxyLoop => complete(StatusCodes.BadGateway -> "Detected proxy loop")
    case NoLeader => complete(StatusCodes.ServiceUnavailable -> "Leader Currently not available")
    case ProxyToLeader(request, localHostPort, leaderHostPort @ HostPort(host, port)) =>
      val forward = request
        // adjust host and port
        .withUri(request.uri.withHost(host).withPort(port.toInt))
        // reverse proxies should filter those headers
        .withHeaders(request.headers.filterNot(header => FilterHeaders(header.name)))
        // add header to indicate this is forwarded via
        .addHeader(`X-Marathon-Via`(localHostPort))
      onComplete(Http().singleRequest(forward)) {
        case Success(response) =>
          val enrichedResponse = response
            // also give the client the via header
            .addHeader(`X-Marathon-Via`(localHostPort))
            // show the client the location of the leading master
            .addHeader(`X-Marathon-Leader`(leaderHostPort))
          complete(enrichedResponse)
        case Failure(ex) =>
          logger.warn(s"Failed to proxy response from leader: ${ex.getMessage}", ex)
          complete(StatusCodes.BadGateway -> "Failed to successfully establish a connection to the leader.")
      }
  }
}
