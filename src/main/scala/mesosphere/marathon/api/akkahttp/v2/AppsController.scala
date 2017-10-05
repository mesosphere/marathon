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

  private val forceParameter = parameter('force.as[Boolean].?(false))

  private def listApps(implicit identity: Identity): Route = ???

  private def replaceMultipleApps(implicit identity: Identity): Route = ???

  private def patchMultipleApps(implicit identity: Identity): Route = ???

  private def createApp(implicit identity: Identity): Route = ???

  private def showApp(appId: PathId)(implicit identity: Identity): Route = ???

  private def patchSingle(appId: PathId)(implicit identity: Identity): Route = ???

  private def putSingle(appId: PathId)(implicit identity: Identity): Route = ???

  private def deleteSingle(appId: PathId)(implicit identity: Identity): Route = ???

  private def restartApp(appId: PathId)(implicit identity: Identity): Route = ???

  private def listRunningTasks(appId: PathId)(implicit identity: Identity): Route = ???

  private def killTasks(appId: PathId)(implicit identity: Identity): Route = ???

  private def killTask(appId: PathId, taskId: TaskId)(implicit identity: Identity): Route = ???

  private def listVersions(appId: PathId)(implicit identity: Identity): Route = ???

  private def getVersion(appId: PathId, version: Timestamp)(implicit identity: Identity): Route = ???

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
