package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.api.akkahttp.{ Controller, Rejections }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.RuntimeConfiguration
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import mesosphere.marathon.stream.UriIO

import scala.async.Async._
import scala.concurrent.ExecutionContext

case class LeaderController(
    val electionService: ElectionService,
    val runtimeConfigRepo: RuntimeConfigurationRepository)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext) extends Controller with Validation with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  def leaderInfo(): Route =
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        electionService.leaderHostPort match {
          case None => reject(Rejections.EntityNotFound.noLeader())
          case Some(leader) => complete(raml.LeaderInfo(leader))
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  def deleteLeader(): Route =
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        authorized(UpdateResource, AuthorizedResource.SystemConfig).apply {
          parameters('backup.?, 'restore.?) { (backup: Option[String], restore: Option[String]) =>
            val validate = optional(UriIO.valid)
            assumeValid(validate(backup) and validate(restore)) {
              complete {
                async {
                  await(runtimeConfigRepo.store(RuntimeConfiguration(backup, restore)))
                  electionService.abdicateLeadership()
                  raml.Message("Leadership abdicated")
                }
              }
            }
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
