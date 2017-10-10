package mesosphere.marathon
package api.akkahttp
package v2

import java.time.Clock

import akka.event.EventStream
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, InfoEmbedResolver, LabelSelectorParsers }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authorizer, CreateRunSpec, Identity, ViewResource, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state.{ AppDefinition, Identifiable, PathId }
import play.api.libs.json.Json
import PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.task.Task.{ Id => TaskId }
import PathMatchers._
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.{ ExecutionContext, Future }

class AppsController(
    val clock: Clock,
    val eventBus: EventStream,
    val marathonSchedulerService: MarathonSchedulerService,
    val appInfoService: AppInfoService,
    val healthCheckManager: HealthCheckManager,
    val instanceTracker: InstanceTracker,
    val taskKiller: TaskKiller,
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

  import AppHelpers._
  import EntityMarshallers._

  import mesosphere.marathon.api.v2.json.Formats._

  private val forceParameter = parameter('force.as[Boolean].?(false))

  private def listApps(implicit identity: Identity): Route = {
    parameters('cmd.?, 'id.?, 'label.?, 'embed.*) { (cmd, id, label, embed) =>
      def index: Future[Seq[AppInfo]] = {
        val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
        val selector = selectAuthorized(search(cmd, id, label))
        appInfoService.selectAppsBy(selector, resolvedEmbed)
      }
      onSuccess(index)(apps => complete(Json.obj("apps" -> apps)))
    }
  }

  private[v2] def search(cmd: Option[String], id: Option[String], label: Option[String]): AppSelector = {
    def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase
    val selectors = Seq[Option[Selector[AppDefinition]]](
      cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
      id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
      label.map(new LabelSelectorParsers().parsed)
    ).flatten
    Selector.forall(selectors)
  }

  private[v2] def selectAuthorized(fn: => AppSelector)(implicit identity: Identity): AppSelector = {
    Selector.forall(Seq(authzSelector, fn))
  }

  private def replaceMultipleApps(implicit identity: Identity): Route = ???

  private def patchMultipleApps(implicit identity: Identity): Route = ???

  private def createApp(implicit identity: Identity): Route = {
    (entity(as[AppDefinition]) & forceParameter & extractClientIP & extractUri) { (rawApp, force, remoteAddr, reqUri) =>

      authorized(CreateRunSpec, rawApp).apply {
        def create: Future[(DeploymentPlan, AppInfo)] = {

          val app = rawApp.copy(versionInfo = VersionInfo.OnlyVersion(clock.now()))

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
        onSuccess(create) { (plan, createdApp) =>
          eventBus.publish(ApiPostEvent(remoteAddr.toString, reqUri.toString, createdApp.app))
          complete((StatusCodes.Created, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), createdApp))
        }
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
          reject(Rejections.EntityNotFound.noApp(appId))
        case Some(info) =>
          authorized(ViewResource, info.app).apply {
            complete(Json.obj("app" -> info))
          }
      }
    }
  }

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
          reject(Rejections.EntityNotFound.noApp(nonExistingAppId))
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
}
