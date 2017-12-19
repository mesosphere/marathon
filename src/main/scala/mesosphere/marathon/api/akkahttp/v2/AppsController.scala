package mesosphere.marathon
package api.akkahttp
package v2

import java.time.Clock

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.server.{ Rejection, RejectionError, Route }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.api.akkahttp.AppsDirectives.{ TaskKillingMode, extractTaskKillingMode }
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthorized
import mesosphere.marathon.api.akkahttp.PathMatchers.{ AppPathIdLike, ExistingRunSpecId, RemainingTaskId, Version }
import mesosphere.marathon.api.akkahttp.Rejections.EntityNotFound
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, InfoEmbedResolver, LabelSelectorParsers }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.Task.{ Id => TaskId }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.plugin.auth.{ Authorizer, CreateRunSpec, DeleteRunSpec, Identity, UpdateRunSpec, ViewResource, ViewRunSpec, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.raml.TaskConversion._
import mesosphere.marathon.raml.{ AnyToRaml, AppUpdate, DeploymentResult, VersionList }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Sink
import play.api.libs.json.Json
import PathMatchers.forceParameter
import mesosphere.marathon.plugin.auth.AuthorizedResource.SystemConfig

import scala.async.Async._
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
    val materializer: Materializer,
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

  private def replaceMultipleApps(implicit identity: Identity): Route = {
    parameters('partialUpdate.?(true)) { partialUpdate =>
      updateMultiple(partialUpdate, allowCreation = true)
    }
  }

  private def patchMultipleApps(implicit identity: Identity): Route = {
    updateMultiple(partialUpdate = true, allowCreation = false)
  }

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
        onSuccessLegacy(create).apply {
          case (plan, createdApp) =>
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
          authorized(ViewRunSpec, info.app).apply {
            complete(Json.obj("app" -> info))
          }
      }
    }
  }

  private def patchSingle(appId: PathId)(implicit identity: Identity): Route =
    update(appId, partialUpdate = true, allowCreation = false)

  private[this] def updateMultiple(partialUpdate: Boolean, allowCreation: Boolean)(implicit identity: Identity): Route = {
    val version = clock.now()
    (forceParameter & entity(as(appUpdatesUnmarshaller(partialUpdate)))) { (force, appUpdates) =>
      val updateGroupFn = updateAppsRootGroupModifier(appUpdates, partialUpdate, allowCreation, version)
      onSuccessLegacy(groupManager.updateRoot(PathId.empty, updateGroupFn, version, force)).apply { plan =>
        complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
      }
    }
  }

  /**
    * Helper function to update apps inside rootgroup
    *
    * @param appUpdates application updates
    * @param partialUpdate do we allow partial updates or not
    * @param allowCreation can we create a new app if appId is missing
    * @param version version
    * @return function for updating rootgroup
    */
  private[v2] def updateAppsRootGroupModifier(
    appUpdates: Seq[AppUpdate],
    partialUpdate: Boolean,
    allowCreation: Boolean,
    version: Timestamp)(implicit identity: Identity): RootGroup => RootGroup = { rootGroup: RootGroup =>
    appUpdates.foldLeft(rootGroup) { (group, update) =>
      update.id.map(PathId(_)) match {
        case Some(id) =>
          group.updateApp(id, AppHelpers.updateOrCreate(id, _, update, partialUpdate, allowCreation, clock.now(), marathonSchedulerService), version)
        case None =>
          group
      }
    }
  }

  private def putSingle(appId: PathId)(implicit identity: Identity): Route =
    parameter('partialUpdate.as[Boolean].?(true)) { partialUpdate =>
      update(appId, partialUpdate = partialUpdate, allowCreation = true)
    }

  /**
    * Internal representation of `replace or update` logic.
    *
    * @param appId appId
    * @param partialUpdate partial update?
    * @param allowCreation is creation allowed?
    * @param identity implicit identity
    * @return http servlet response
    */
  private[this] def update(appId: PathId, partialUpdate: Boolean, allowCreation: Boolean)(implicit identity: Identity): Route = {
    val version = clock.now()

    (forceParameter &
      extractClientIP &
      extractUri &
      entity(as(appUpdateUnmarshaller(appId, partialUpdate)))) { (force, remoteAddr, requestUri, appUpdate) =>
        // Note - this function throws exceptions and handles authorization synchronously. We need to catch and map these
        // exceptions to the appropriate rejections
        def fn(appDefinition: Option[AppDefinition]) = updateOrCreate(
          appId, appDefinition, appUpdate, partialUpdate, allowCreation, clock.now(), marathonSchedulerService)

        onSuccessLegacy(groupManager.updateApp(appId, fn, version, force)).apply { plan =>
          plan.target.app(appId).foreach { appDef =>
            eventBus.publish(ApiPostEvent(remoteAddr.toString, requestUri.toString, appDef))
          }

          completeWithDeploymentForApp(appId, plan)
        }
      }
  }

  private def deleteSingle(appId: PathId)(implicit identity: Identity): Route =
    forceParameter { force =>
      onSuccess(groupManager.updateRootEither(appId.parent, deleteAppRootGroupModifier(appId), force = force)) {
        case Right(plan) =>
          completeWithDeploymentForApp(appId, plan)
        case Left(rej) =>
          reject(rej)
      }
    }

  /**
    * We have to pass this function to the groupManager to make sure updates are serialized
    *
    * Another request might remove the app before we call the update.
    * That is why we cannot check if the app exists before the update call.
    * We have to do so during the call. Since we do some app handling anyways we can also check if the caller is authroized.
    *
    *
    * @param appId id of the app
    * @param identity
    * @return updated RootGroup in case of success, Rejection otherwise
    */
  private[v2] def deleteAppRootGroupModifier(appId: PathId)(implicit identity: Identity): RootGroup => Future[Either[Rejection, RootGroup]] = { rootGroup: RootGroup =>
    rootGroup.app(appId) match {
      case None =>
        Future.successful(Left(Rejections.EntityNotFound.noApp(appId)))
      case Some(app) =>
        if (authorizer.isAuthorized(identity, DeleteRunSpec, app))
          Future.successful(Right(rootGroup.removeApp(appId)))
        else
          Future.successful(Left(NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _)))))
    }
  }

  private def restartApp(appId: PathId)(implicit identity: Identity): Route = {
    forceParameter { force =>
      val newVersion = clock.now()
      onSuccessLegacy(
        groupManager.updateApp(
          appId,
          markAppForRestarting(appId),
          newVersion, force)
      ).apply { restartDeployment =>
          completeWithDeploymentForApp(appId, restartDeployment)
        }
    }
  }

  private[v2] def markAppForRestarting(appId: PathId)(implicit identity: Identity): Option[AppDefinition] => AppDefinition = { maybeAppDef =>
    val appDefinition = maybeAppDef.getOrElse(throw RejectionError(Rejections.EntityNotFound.noApp(appId)))
    if (authorizer.isAuthorized(identity, UpdateRunSpec, appDefinition)) {
      appDefinition.markedForRestarting
    } else {
      throw RejectionError(NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def listRunningTasks(appId: PathId)(implicit identity: Identity): Route = {
    groupManager.app(appId) match {
      case Some(app) =>
        authorized(ViewRunSpec, app).apply {
          val tasksF = async {
            val instancesBySpec = await(instanceTracker.instancesBySpec)
            await(runningTasks(Set(appId), instancesBySpec).runWith(Sink.seq)).map(_.toRaml)
          }
          onSuccess(tasksF) { tasks =>
            complete(raml.TaskList(tasks))
          }
        }
      case None =>
        reject(Rejections.EntityNotFound.noApp(appId))
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def runningTasks(appIds: Set[PathId], instancesBySpec: InstancesBySpec): Source[EnrichedTask, NotUsed] = {
    Source(appIds).mapAsync(1) { appId =>
      async {
        val instances = instancesBySpec.specInstances(appId)
        val healthStatuses = await(healthCheckManager.statuses(appId))
        instances.flatMap { instance =>
          val health = healthStatuses.getOrElse(instance.instanceId, Nil)
          instance.tasksMap.values.map { task => EnrichedTask(appId, task, instance.agentInfo, health) }
        }
      }
    }
      .mapConcat(identity)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def killTasks(appId: PathId)(implicit identity: Identity): Route = {
    // the line below doesn't look nice but it doesn't compile if we use parameters directive
    (forceParameter & parameter("host") & extractTaskKillingMode) {
      (force, host, mode) =>
        def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
          appTasks.filter(_.agentInfo.host == host || host == "*")
        }
        mode match {
          case TaskKillingMode.Scale =>
            val deploymentPlan = taskKiller.killAndScale(appId, findToKill, force)
            onSuccess(deploymentPlan) { plan =>
              complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
            }
          case TaskKillingMode.Wipe =>
            val killInstances = async {
              val instances = await(taskKiller.kill(appId, findToKill, wipe = true))
              instances.map { instance =>
                EnrichedTask(appId, instance.appTask, instance.agentInfo, Nil)
              }
            }

            onSuccess(killInstances) { enrichedTasks: Seq[EnrichedTask] =>
              complete((StatusCodes.OK, raml.TaskList(enrichedTasks.toRaml)))
            }
          case TaskKillingMode.KillWithoutWipe =>
            val killInstances = async {
              val instances = await(taskKiller.kill(appId, findToKill, wipe = false))
              instances.map { instance =>
                EnrichedTask(appId, instance.appTask, instance.agentInfo, Nil)
              }
            }

            onSuccess(killInstances) { enrichedTasks: Seq[EnrichedTask] =>
              complete((StatusCodes.OK, raml.TaskList(enrichedTasks.toRaml)))
            }
        }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def killTask(appId: PathId, taskId: TaskId)(implicit identity: Identity): Route = {
    // the line below doesn't look nice but it doesn't compile if we use parameters directive
    (forceParameter & parameter("host") & extractTaskKillingMode) {
      (force, host, mode) =>
        def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
          try {
            val instanceId = taskId.instanceId
            appTasks.filter(_.instanceId == instanceId)
          } catch {
            // the id can not be translated to an instanceId
            case _: MatchError => Seq.empty
          }
        }
        mode match {
          case TaskKillingMode.Scale =>
            val deploymentPlanF = taskKiller.killAndScale(appId, findToKill, force)
            onSuccess(deploymentPlanF) { plan =>
              complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
            }
          case TaskKillingMode.Wipe =>
            val killedInstance = async {
              val instances = await(taskKiller.kill(appId, findToKill, wipe = true))
              val healthStatuses = await(healthCheckManager.statuses(appId))
              instances.headOption.map { instance =>
                EnrichedTask(appId, instance.appTask, instance.agentInfo, healthStatuses.getOrElse(instance.instanceId, Nil))
              }
            }

            onSuccess(killedInstance) {
              case None =>
                reject(EntityNotFound.noTask(taskId))
              case Some(enrichedTask) =>
                complete(raml.TaskSingle(enrichedTask.toRaml))
            }
          case TaskKillingMode.KillWithoutWipe =>
            val killedInstance = async {
              val instances = await(taskKiller.kill(appId, findToKill, wipe = false))
              val healthStatuses = await(healthCheckManager.statuses(appId))
              instances.headOption.map { instance =>
                EnrichedTask(appId, instance.appTask, instance.agentInfo, healthStatuses.getOrElse(instance.instanceId, Nil))
              }
            }

            onSuccess(killedInstance) {
              case None =>
                reject(EntityNotFound.noTask(taskId))
              case Some(enrichedTask) =>
                complete(raml.TaskSingle(enrichedTask.toRaml))
            }
        }
    }
  }

  private def listVersions(appId: PathId)(implicit identity: Identity): Route = {
    val versions = groupManager.appVersions(appId).runWith(Sink.seq)
    groupManager.app(appId) match {
      case Some(app) =>
        authorized(ViewRunSpec, app, Rejections.EntityNotFound.noApp(appId)).apply {
          onSuccess(versions) { versions =>
            complete(VersionList(versions))
          }
        }
      case None => reject(Rejections.EntityNotFound.noApp(appId))
    }
  }

  private def getVersion(appId: PathId, version: Timestamp)(implicit identity: Identity): Route = {
    onSuccess(groupManager.appVersion(appId, version.toOffsetDateTime)) {
      case Some(app) =>
        authorized(ViewRunSpec, app).apply {
          complete(app.toRaml)
        }
      case None =>
        reject(Rejections.EntityNotFound.noApp(appId))
    }
  }

  //TODO: we probably should refactor this into entity marshaller
  private def completeWithDeploymentForApp(appId: PathId, plan: DeploymentPlan) =
    plan.original.app(appId) match {
      case Some(_) =>
        complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
      case None =>
        complete((StatusCodes.Created, List(Location(Uri(appId.toString)), Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
    }

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
        pathPrefix(ExistingRunSpecId(groupManager.rootGroup)) { appId =>
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

  private implicit val validateAndNormalizeAppUpdate: Normalization[raml.AppUpdate] =
    appUpdateNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())
}
