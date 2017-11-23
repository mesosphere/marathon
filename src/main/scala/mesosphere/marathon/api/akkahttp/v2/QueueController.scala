package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ StatusCodes }
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Directives.{ asLeader, authenticated, get, parameters, pathEndOrSingleSlash, _ }
import mesosphere.marathon.api.akkahttp.EntityMarshallers._
import mesosphere.marathon.api.akkahttp.PathMatchers.AppPathIdLike
import mesosphere.marathon.api.akkahttp.{ Controller, Rejections }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.plugin.auth.{ Authenticator, _ }
import mesosphere.marathon.state.PathId

import scala.async.Async._
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

class QueueController(
    clock: Clock,
    launchQueue: LaunchQueue,
    val electionService: ElectionService)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer
) extends Controller with StrictLogging {

  // format: OFF
  override val route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        get {
          pathEndOrSingleSlash {
            list
          }
        } ~
        delete {
          pathPrefix(AppPathIdLike) { appId =>
            path("delay") {
              reset(appId)
            }
          }
        }
      }
    }
  }
  // format: ON

  @SuppressWarnings(Array("all")) // async/await
  private def list(implicit identity: Identity): Route = {
    def listWithStatisticsAsync(): Future[Seq[LaunchQueue.QueuedInstanceInfoWithStatistics]] = async {
      val info = await(launchQueue.listWithStatisticsAsync)
      info.filter(t => t.inProgress && authorizer.isAuthorized(identity, ViewRunSpec, t.runSpec))
    }

    parameters('embed.*) { embed =>
      val embedLastUnusedOffers = embed.toSeq.contains(QueueController.EmbedLastUnusedOffersParameter)
      onSuccess(listWithStatisticsAsync) { info =>
        complete((info, embedLastUnusedOffers, clock))
      }
    }
  }

  private def reset(appId: PathId)(implicit identity: Identity): Route = {
    onSuccess(launchQueue.listAsync) { apps =>
      val maybeApp = apps.find(_.runSpec.id == appId).map(_.runSpec)

      maybeApp match {
        case Some(app) =>
          authorized(UpdateRunSpec, app).apply {
            launchQueue.resetDelay(app)
            complete(StatusCodes.NoContent)
          }
        case None => reject(Rejections.EntityNotFound.queueApp(appId))
      }
    }
  }
}

object QueueController {
  val EmbedLastUnusedOffersParameter = "lastUnusedOffers"
}
