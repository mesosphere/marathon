package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.Directives.{asLeader, authenticated, get, parameters, pathEndOrSingleSlash, _}
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, Identity, ViewRunSpec}

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

class QueueController(
    clock: Clock,
    launchQueue: LaunchQueue)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller with StrictLogging {

  override val route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        get {
          pathEndOrSingleSlash {
            list
          }
        }
      }
    }
  }

  private def list(implicit identity: Identity): Route = {
    def listAsync(): Future[Seq[LaunchQueue.QueuedInstanceInfoWithStatistics]] = async {
      val info = await(launchQueue.listWithStatisticsAsync)
      info.filter(t => t.inProgress && authorizer.isAuthorized(identity, ViewRunSpec, t.runSpec))
    }

    parameters('embed.*) { embed =>
      val embedLastUnusedOffers = embed.exists(_ == QueueController.EmbedLastUnusedOffers)
      onComplete(listAsync) { info =>
        complete("TODO")
      }
    }
  }
}

object QueueController {
  val EmbedLastUnusedOffers = "lastUnusedOffers"
}