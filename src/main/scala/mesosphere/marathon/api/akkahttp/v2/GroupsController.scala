package mesosphere.marathon
package api.akkahttp
package v2

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{ Directive1, Route }
import mesosphere.marathon.api.akkahttp.PathMatchers.GroupPathIdLike
import mesosphere.marathon.api.akkahttp.v2.GroupsController._
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, PodsResource }
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo, GroupInfoService, Selector }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authorizer, Identity, ViewGroup, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state.{ Group, PathId, Timestamp }
import play.api.libs.json.Json
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import mesosphere.marathon.api.akkahttp.PathMatchers.{ AppPathIdLike, GroupPathIdLike }
import mesosphere.marathon.api.akkahttp.Rejections.EntityNotFound
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.core.appinfo.GroupInfoService
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth.{ Authorizer, ViewGroup, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.raml.GroupUpdate
import mesosphere.marathon.stream.Sink

import scala.concurrent.ExecutionContext

class GroupsController(
    electionService: ElectionService,
    infoService: GroupInfoService,
    groupManager: GroupManager,
    val config: MarathonConf)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer,
    val materializer: Materializer
) extends Controller {
  import Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._
  import mesosphere.marathon.api.v2.json.Formats._
  import mesosphere.marathon.raml.GroupConversion._

  private val forceParameter = parameter('force.as[Boolean].?(false))

  /** convert app to canonical form */
  private implicit val appNormalization: Normalization[raml.App] = {
    val appNormalizationConfig = AppNormalization.Configuration(
      config.defaultNetworkName.get,
      config.mesosBridgeName())
    AppHelpers.appNormalization(config.availableFeatures, appNormalizationConfig)
  }

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

  def createGroup(groupId: PathId)(implicit identity: Identity): Route = {
    (forceParameter & entity(as(groupUpdateUnmarshaller(groupId)))) { (force, groupUpdate) =>
      val effectiveGroupId = groupUpdate.id.map(id => PathId(id).canonicalPath(groupId)).getOrElse(groupId)
      val rootGroup = groupManager.rootGroup()

      def throwIfConflicting[A](conflict: Option[Any], msg: String) = {
        conflict.map(_ => throw ConflictingChangeException(msg))
      }

      throwIfConflicting(
        rootGroup.group(effectiveGroupId),
        s"Group $effectiveGroupId is already created. Use PUT to change this group.")

      throwIfConflicting(
        rootGroup.transitiveAppsById.get(effectiveGroupId),
        s"An app with the path $effectiveGroupId already exists.")

      // groupManager.updateRoot(
      // effectiveGroupId.parent, applyGroupUpdate(_, effectiveGroupId, update, version), version, force)
      // val (deployment, path) = updateOrCreate(effectivePath, groupUpdate, force)
      // deploymentResult(deployment, Response.created(new URI(path.toString)))
      complete(null)
    }
  }

  def updateGroup(groupId: PathId): Route = ???

  def deleteGroup(groupId: PathId): Route = ???

  def listVersions(groupId: PathId)(implicit identity: Identity): Route = {
    groupManager.group(groupId) match {
      case Some(group) =>
        authorized(ViewGroup, group).apply {
          val versionsFuture = groupManager.versions(groupId).runWith(Sink.seq)
          onSuccess(versionsFuture) { versions =>
            complete(versions)
          }
        }
      case None => reject(EntityNotFound.noGroup(groupId))
    }
  }

  def versionDetail(groupId: PathId, version: Timestamp)(implicit identity: Identity): Route = extractEmbeds {
    case (appEmbed, groupEmbed) =>
      onSuccess(infoService.selectGroupVersion(groupId, version, authorizationSelectors, groupEmbed)) {
        case Some(info) => complete(Json.toJson(info))
        case None => reject(EntityNotFound.noGroup(groupId, Some(version)))
      }
  }

  // format: OFF
  val route: Route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        path(GroupPathIdLike) { maybeGroupId =>
          withValidatedPathId(maybeGroupId.toString) { groupId =>
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
          }
        } ~
        path(GroupPathIdLike ~ Slash.? ~ "apps" ~ Slash.? ~ PathEnd) { groupId =>
          get {
            appsList(groupId)
          }
        } ~
        pathPrefix(GroupPathIdLike ~ Slash.? ~ "versions") { groupId =>
          pathEndOrSingleSlash {
            get {
              listVersions(groupId)
            }
          } ~
          path(Remaining ~ Slash.? ~ PathEnd) { version =>
            get {
              versionDetail(groupId, Timestamp(version))
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
