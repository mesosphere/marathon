package mesosphere.marathon
package api.akkahttp
package v2

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.PathMatchers.{ AppPathIdLike, GroupPathIdLike }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authorizer, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state.PathId

import scala.concurrent.ExecutionContext

class GroupsController(electionService: ElectionService)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer
) extends Controller {
  import Directives._

  def groupDetail(groupId: PathId): Route = ???

  def appsList(groupId: PathId): Route = ???

  def createGroup(groupId: PathId): Route = ???

  def updateGroup(groupId: PathId): Route = ???

  def deleteGroup(groupId: PathId): Route = ???

  def listVersions(groupId: PathId): Route = ???

  def versionDetail(groupId: PathId, version: String): Route = ???

  val route: Route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        path(GroupPathIdLike) { groupId =>
          pathEndOrSingleSlash {
            get {
              groupDetail(groupId)
            } ~
              post {
                createGroup(groupId)
              } ~
              put {
                updateGroup(groupId)
              } ~
              delete {
                deleteGroup(groupId)
              }
          }
        } ~
          path(GroupPathIdLike ~ "apps" ~ Slash.? ~ PathEnd) { groupId =>
            get {
              appsList(groupId)
            }
          } ~
          path(GroupPathIdLike / "versions") { groupId =>
            pathEndOrSingleSlash {
              get {
                listVersions(groupId)
              }
            } ~
              path(Remaining ~ Slash.? ~ PathEnd) { version =>
                get {
                  versionDetail(groupId, version)
                }
              }
          }
      }
    }
  }
}
