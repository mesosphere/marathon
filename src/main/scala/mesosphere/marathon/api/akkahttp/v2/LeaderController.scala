package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.{ Controller, Rejections }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.raml.RuntimeConfiguration
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository

import scala.async.Async._
import scala.concurrent.ExecutionContext

case class LeaderController(
    val electionService: ElectionService,
    val runtimeConfigRepo: RuntimeConfigurationRepository)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  def leaderInfo(): Route =
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        electionService.leaderHostPort match {
          case None => reject(Rejections.EntityNotFound.leader())
          case Some(leader) => complete(raml.LeaderInfo(leader))
        }
      }
    }

  def deleteLeader(): Route =
    authenticated.apply { implicit identity =>
      authorized(UpdateResource, AuthorizedResource.SystemConfig).apply {
        parameters('backup.?, 'restore.?) { (backup, restore) =>
          // This follows the LeaderResource implementation. Seems like a bug to me. Why should we not proxy the request?
          if (electionService.isLeader) {

            //TODO: validate backup and restore parameters
            complete {
              async {
                await(runtimeConfigRepo.store(RuntimeConfiguration(backup, restore)))
                electionService.abdicateLeadership()
                raml.Message("Leadership abdicated")
              }
            }
          } else {
            reject(Rejections.EntityNotFound.leader())
          }
        }
      }
    }

  override val route: Route = {
    get {
      pathEndOrSingleSlash {
        leaderInfo()
      }
    } ~
      delete {
        pathEndOrSingleSlash {
          deleteLeader()
        }
      }
  }
}
