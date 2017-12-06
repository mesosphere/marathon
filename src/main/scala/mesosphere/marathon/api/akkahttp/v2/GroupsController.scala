package mesosphere.marathon
package api.akkahttp
package v2

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Directive1, Rejection, Route }
import akka.stream.Materializer
import mesosphere.marathon.api.GroupApiService
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthorized
import mesosphere.marathon.api.akkahttp.PathMatchers.GroupPathIdLike
import mesosphere.marathon.api.akkahttp.Rejections.{ EntityNotFound, Message }
import mesosphere.marathon.api.v2.GroupsResource.normalizeApps
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, PodsResource }
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo, GroupInfoService, Selector }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth.{ Authorizer, DeleteGroup, Identity, ViewGroup, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.raml.DeploymentResult
import mesosphere.marathon.state.{ Group, PathId, RootGroup, Timestamp }
import mesosphere.marathon.stream.Sink
import play.api.libs.json.Json

import scala.async.Async._
import scala.concurrent.{ Await, Awaitable, ExecutionContext, Future }
import scala.util.{ Failure, Success }

class GroupsController(
    electionService: ElectionService,
    infoService: GroupInfoService,
    groupManager: GroupManager,
    groupApiService: GroupApiService,
    val config: MarathonConf)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer,
    val materializer: Materializer
) extends Controller {
  import Directives._
  import mesosphere.marathon.api.v2.json.Formats._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  private val forceParameter = parameter('force.as[Boolean].?(false))

  /** convert app to canonical form */
  private val appNormalization: Normalization[raml.App] = {
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
    (forceParameter & entity(as[raml.GroupUpdate])) { (force, groupUpdate) =>
      val effectiveGroupId = groupUpdate.id.map(id => PathId(id).canonicalPath(groupId)).getOrElse(groupId)
      val rootGroup = groupManager.rootGroup()
      val groupValidator = Group.validNestedGroupUpdateWithBase(effectiveGroupId)
      assumeValid(groupValidator.apply(normalizeApps(effectiveGroupId, groupUpdate)(appNormalization))) {
        if (rootGroup.group(effectiveGroupId).isDefined) { // group already exists
          reject(Rejections.ConflictingChange(Message(s"Group $effectiveGroupId is already created. Use PUT to change this group.")))
        } else if (rootGroup.exists(effectiveGroupId)) { // app with the group id already exists
          reject(Rejections.ConflictingChange(Message(s"An app with the path $effectiveGroupId already exists.")))
        } else {
          onComplete(updateOrCreate(groupId, groupUpdate, force)) {
            case Success(deploymentResult) => complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(deploymentResult.deploymentId)), deploymentResult))
            case Failure(ex: IllegalArgumentException) => complete(StatusCodes.UnprocessableEntity -> ex.getMessage)
            case Failure(ex) => throw ex
          }
        }
      }
    }
  }

  /**
    * Until there is async version of updateRoot we have to block here
    */
  protected def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)

  @SuppressWarnings(Array("all")) // async/await
  private def updateOrCreate(id: PathId, update: raml.GroupUpdate, force: Boolean)(implicit identity: Identity): Future[DeploymentResult] = async {
    val version = Timestamp.now()

    val effectivePath = update.id.map(PathId(_).canonicalPath(id)).getOrElse(id)
    val deploymentPlan = await(groupManager.updateRootAsync(
      id.parent, group => groupApiService.updateGroup(group, effectivePath, update, version), version, force))
    DeploymentResult(deploymentPlan.id, deploymentPlan.version.toOffsetDateTime)
  }

  def updateGroup(groupId: PathId): Route = ???

  def deleteGroup(groupId: PathId)(implicit identity: Identity): Route = {
    forceParameter { force =>
      val version = Timestamp.now()

      def deleteGroupEither(rootGroup: RootGroup): Future[Either[Rejection, RootGroup]] = {
        rootGroup.group(groupId) match {
          case Some(group) if !authorizer.isAuthorized(identity, DeleteGroup, group) =>
            Future.successful(Left(NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _)))))
          case Some(_) => Future.successful(Right(rootGroup.removeGroup(groupId, version)))
          case None => Future.successful(Left(Rejections.EntityNotFound.noGroup(groupId)))
        }
      }

      onSuccess(groupManager.updateRootEither(groupId.parent, deleteGroupEither, version, force)) {
        case Left(rejection) => reject(rejection)
        case Right(plan) =>
          complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
      }
    }
  }

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
        pathPrefix(GroupPathIdLike ~ Slash.? ~ "apps") { groupId =>
          pathEndOrSingleSlash {
            get {
              appsList(groupId)
            }
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

  /**
    * Initializes rules for selecting groups to take authorization into account
    */
  def authorizationSelectors(implicit identity: Identity): GroupInfoService.Selectors = {
    GroupInfoService.Selectors(
      AppHelpers.authzSelector,
      PodsResource.authzSelector,
      authzSelector)
  }

  private def authzSelector(implicit authz: Authorizer, identity: Identity) = Selector[Group] { g =>
    authz.isAuthorized(identity, ViewGroup, g)
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
