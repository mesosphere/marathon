package mesosphere.marathon
package api.akkahttp
package v2

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{ Directive1, Route }
import mesosphere.marathon.api.akkahttp.PathMatchers.GroupPathIdLike
import mesosphere.marathon.api.akkahttp.v2.GroupsController._
import mesosphere.marathon.api.v2.{ AppHelpers, PodsResource }
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo, GroupInfoService, Selector }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authorizer, Identity, ViewGroup, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state.{ Group, PathId }
import play.api.libs.json.Json
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.PathMatchers.{ AppPathIdLike, GroupPathIdLike }
import mesosphere.marathon.core.appinfo.GroupInfoService
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authorizer, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state.PathId

import scala.concurrent.ExecutionContext

class GroupsController(electionService: ElectionService, infoService: GroupInfoService)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer
) extends Controller {
  import Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._
  import mesosphere.marathon.api.v2.json.Formats._

  def groupDetail(groupId: PathId)(implicit identity: Identity): Route = {
    extractEmbeds {
      case (appEmbed, groupEmbed) =>
        onSuccess(infoService.selectGroup(groupId, authorizationSelectors, appEmbed, groupEmbed)) {
          case Some(info) => complete(Json.toJson(info))
          case None if groupId.isRoot => complete(Json.toJson(GroupInfo.empty))
          case None => reject(Rejections.EntityNotFound.noGroup(groupId))
        }
    }
  }

  def appsList(groupId: PathId): Route = ???

  def createGroup(groupId: PathId): Route = ???

  def updateGroup(groupId: PathId): Route = ???

  def deleteGroup(groupId: PathId): Route = ???

  def listVersions(groupId: PathId): Route = ???

  def versionDetail(groupId: PathId, version: String): Route = ???

  // format: OFF
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
  // format: On

  def authorizationSelectors(implicit identity: Identity): GroupInfoService.Selectors = {
    GroupInfoService.Selectors(
      AppHelpers.authzSelector,
      PodsResource.authzSelector,
      authzSelector)
  }

  import mesosphere.marathon.api.v2.InfoEmbedResolver._
  /**
    * For backward compatibility, we embed always apps, pods, and groups if nothing is specified.
    */
  val defaultEmbeds = Set(EmbedApps, EmbedPods, EmbedGroups)
  def extractEmbeds: Directive1[(Set[AppInfo.Embed], Set[GroupInfo.Embed])] = {
    parameter('embed.*).tflatMap {
      case Tuple1(embeds) => provide(resolveAppGroup(if (embeds.isEmpty) defaultEmbeds else embeds.toSet))
    }
  }
}

object GroupsController {

  private def authzSelector(implicit authz: Authorizer, identity: Identity) = Selector[Group] { g =>
    authz.isAuthorized(identity, ViewGroup, g)
  }
}
