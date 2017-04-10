package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.Directives.{ pass, reject, complete, extractRequest }
import akka.http.scaladsl.server._
import mesosphere.marathon.api.akkahttp.LeaderDirectives.NoLeaderRejection
import mesosphere.marathon.core.election.ElectionService

trait LeaderDirectives {
  /**
    * Directive which, given an election service, rejects with NoLeaderRejection if not currently leader.
    *
    * The HTTP service's rejection handler should handle proxying via the handleNonLeader partialFunction below
    */
  def asLeader(electionService: ElectionService): Directive0 = {
    extractRequest.flatMap { request =>
      if (electionService.isLeader) {
        pass
      } else {
        reject(NoLeaderRejection(request, electionService.leaderHostPort))
      }
    }
  }
}

object LeaderDirectives {
  private[LeaderDirectives] case class NoLeaderRejection(request: HttpRequest, leaderHost: Option[String]) extends Rejection

  def handleNonLeader: PartialFunction[Rejection, Route] = {
    case NoLeaderRejection(_, None) => complete(StatusCodes.ServiceUnavailable -> "Leader Currently not available")
    case NoLeaderRejection(request, Some(currentLeader)) => complete(StatusCodes.EnhanceYourCalm -> s"proxy the request to $currentLeader/${request.uri.path}?${request.uri.rawQueryString} (TODO)")
  }
}
