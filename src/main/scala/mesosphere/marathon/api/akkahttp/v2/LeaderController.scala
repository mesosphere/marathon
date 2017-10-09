package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.{ Controller, Rejections }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedResource, Authorizer, ViewResource }
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.ExecutionContext

case class LeaderController(val electionService: ElectionService)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends Controller with StrictLogging {

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

  def deleteLeader(): Route = ???

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
