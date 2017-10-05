package mesosphere.marathon
package api.akkahttp
package v2

import java.time.Clock

import akka.event.EventStream
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, InfoEmbedResolver, LabelSelectorParsers }
import mesosphere.marathon.api.akkahttp.{ Controller, EntityMarshallers }
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.api.v2.AppHelpers.{ appNormalization, appUpdateNormalization, authzSelector }
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authenticator => MarathonAuthenticator, Authorizer, CreateRunSpec, Identity, ViewResource }
import mesosphere.marathon.state.{ AppDefinition, Identifiable, PathId }
import play.api.libs.json.Json
import PathId._
import mesosphere.marathon.core.election.ElectionService

import scala.concurrent.{ ExecutionContext, Future }

class AppsController(
    val clock: Clock,
    val eventBus: EventStream,
    val service: MarathonSchedulerService,
    val appInfoService: AppInfoService,
    val config: MarathonConf,
    val groupManager: GroupManager,
    val pluginManager: PluginManager)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller {
  import Directives._

  private implicit lazy val validateApp = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)
  private implicit lazy val updateValidator = AppValidation.validateCanonicalAppUpdateAPI(config.availableFeatures, () => normalizationConfig.defaultNetworkName)

  import AppHelpers._
  import EntityMarshallers._

  import mesosphere.marathon.api.v2.json.Formats._

  private def listApps(implicit identity: Identity): Route = {
    parameters('cmd.?, 'id.?, 'label.?, 'embed.*) { (cmd, id, label, embed) =>
      def index: Future[Seq[AppInfo]] = {
        def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase

        val selectors = Seq[Option[Selector[AppDefinition]]](
          cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
          id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
          label.map(new LabelSelectorParsers().parsed),
          Some(authzSelector)
        ).flatten
        val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
        appInfoService.selectAppsBy(Selector.forall(selectors), resolvedEmbed)
      }
      onSuccess(index)(apps => complete(Json.obj("apps" -> apps)))
    }
  }

  private def createApp(app: AppDefinition, force: Boolean)(implicit identity: Identity): Route = {
    def create: Future[(DeploymentPlan, AppInfo)] = {

      def createOrThrow(opt: Option[AppDefinition]) = opt
        .map(_ => throw ConflictingChangeException(s"An app with id [${app.id}] already exists."))
        .getOrElse(app)

      groupManager.updateApp(app.id, createOrThrow, app.version, force).map { plan =>
        val appWithDeployments = AppInfo(
          app,
          maybeCounts = Some(TaskCounts.zero),
          maybeTasks = Some(Seq.empty),
          maybeDeployments = Some(Seq(Identifiable(plan.id)))
        )
        plan -> appWithDeployments
      }
    }
    authorized(CreateRunSpec, app).apply {
      onSuccess(create) { (plan, app) =>
        //TODO: post ApiPostEvent
        complete((StatusCodes.Created, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), app))
      }
    }
  }

  private def showApp(appId: PathId)(implicit identity: Identity): Route = {
    parameters('embed.*) { embed =>
      val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) ++ Set(
        // deprecated. For compatibility.
        AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
      )

      onSuccess(appInfoService.selectApp(appId, authzSelector, resolvedEmbed)) {
        case None =>
          reject(Rejections.EntityNotFound.app(appId))
        case Some(info) =>
          authorized(ViewResource, info.app).apply {
            complete(Json.obj("app" -> info))
          }
      }
    }
  }

  val RemainingPathId = RemainingPath.map(_.toString.toRootPath)

  // format: OFF
  val route: Route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        pathEndOrSingleSlash {
          get {
            listApps
          } ~
          put {
            replaceMultipleApps
          } ~
          patch {
            patchMultipleApps
          } ~
          post {
            createApp
          }
        } ~
        pathPrefix(ExistingAppPathId(groupManager.rootGroup)) { appId =>
          pathEndOrSingleSlash {
            get {
              showApp(appId)
            } ~
            patch {
              patchSingle(appId)
            } ~
            put {
              putSingle(appId)
            } ~
            delete {
              deleteSingle(appId)
            }
          } ~
          (path("restart") & post) {
            restartApp(appId)
          } ~
          pathPrefix("tasks") {
            pathEndOrSingleSlash {
              get {
                listRunningTasks(appId)
              } ~
              delete {
                killTasks(appId)
              }
            } ~
            (pathPrefix(RemainingTaskId) & delete) { taskId =>
              killTask(appId, taskId)
            }
          } ~
          pathPrefix("versions") {
            (pathEnd & get) {
              listVersions(appId)
            } ~
            path(Version) { version =>
              getVersion(appId, version)
            }
          }
        } ~
        path(AppPathIdLike) { nonExistingAppId =>
          reject(Rejections.EntityNotFound.app(nonExistingAppId))
        }
      }
    }
  }
  // format: ON

  private val normalizationConfig = AppNormalization.Configuration(
    config.defaultNetworkName.get,
    config.mesosBridgeName()
  )

  private implicit val validateAndNormalizeApp: Normalization[raml.App] =
    appNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())

  private implicit val validateAndNormalizeAppUpdate: Normalization[raml.AppUpdate] =
    appUpdateNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())
}
