package mesosphere.marathon
package api.akkahttp
package v2

import java.time.Clock

import akka.event.EventStream
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directive1, Rejection, RejectionError, Route }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthorized
import mesosphere.marathon.api.akkahttp.PathMatchers.ExistingAppPathId
import mesosphere.marathon.api.v2.{ AppHelpers, AppNormalization, InfoEmbedResolver, LabelSelectorParsers }
import mesosphere.marathon.api.akkahttp.{ Controller, EntityMarshallers }
import mesosphere.marathon.api.v2.AppHelpers.{ appNormalization, appUpdateNormalization, authzSelector }
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.api.v2.AppHelpers.{ appNormalization, appUpdateNormalization, authzSelector }

import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authorizer, CreateRunSpec, DeleteRunSpec, Identity, UpdateRunSpec, ViewResource, ViewRunSpec, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.util.RichEither
import play.api.libs.json._
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.task.Task.{ Id => TaskId }
import PathMatchers._
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.core.task.Task.{ Id => TaskId }
import PathMatchers._
import mesosphere.marathon.raml.DeploymentResult
import mesosphere.marathon.raml.EnrichedTaskConversion._
import mesosphere.marathon.raml.AnyToRaml

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

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
  private implicit lazy val updateValidator = AppValidation.validateCanonicalAppUpdateAPI(config.availableFeatures, () => normalizationConfig.defaultNetworkName)

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

  private def patchSingle(appId: PathId)(implicit identity: Identity): Route =
    update(appId, partialUpdate = true, allowCreation = false)

  private[this] def updateMultiple(partialUpdate: Boolean, allowCreation: Boolean)(implicit identity: Identity): Route = {
    val version = clock.now()
    (forceParameter & entity(as(appUpdatesUnmarshaller(partialUpdate)))) { (force, appUpdates) =>
      def updateGroup(rootGroup: RootGroup): RootGroup = appUpdates.foldLeft(rootGroup) { (group, update) =>
        update.id.map(PathId(_)) match {
          case Some(id) =>
            group.updateApp(id, AppHelpers.updateOrCreate(id, _, update, partialUpdate, allowCreation, clock.now(), marathonSchedulerService), version)
          case None =>
            group
        }
      }

      onSuccessLegacy(None)(groupManager.updateRoot(PathId.empty, updateGroup, version, force)).apply { plan =>
        complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
      }
    }
  }

  /**
    * It'd be neat if we didn't need this. Would take some heavy-ish refactoring to get all of the update functions to
    * take an either.
    */
  private def onSuccessLegacy[T](maybeAppId: Option[PathId])(f: => Future[T])(implicit identity: Identity): Directive1[T] = onComplete({
    try { f }
    catch {
      case NonFatal(ex) =>
        Future.failed(ex)
    }
  }).flatMap {
    case Success(t) =>
      provide(t)
    case Failure(ValidationFailedException(_, failure)) =>
      reject(EntityMarshallers.ValidationFailed(failure))
    case Failure(AccessDeniedException(msg)) =>
      reject(AuthDirectives.NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
    case Failure(_: AppNotFoundException) =>
      reject(
        maybeAppId.map { appId =>
          Rejections.EntityNotFound.noApp(appId)
        } getOrElse Rejections.EntityNotFound()
      )
    case Failure(RejectionError(rejection)) =>
      reject(rejection)
    case Failure(ex) =>
      throw ex
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

        onSuccessLegacy(Some(appId))(groupManager.updateApp(appId, fn, version, force)).apply { plan =>
          plan.target.app(appId).foreach { appDef =>
            eventBus.publish(ApiPostEvent(remoteAddr.toString, requestUri.toString, appDef))
          }

          completeWithDeploymentForApp(appId, plan)
        }
      }
  }

  private def deleteSingle(appId: PathId)(implicit identity: Identity): Route =
    forceParameter { force =>
      lazy val notFound: Either[Rejection, RootGroup] =
        Left(Rejections.EntityNotFound.noApp(appId))

      lazy val notAuthorized: Either[Rejection, RootGroup] =
        Left(NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))

      def deleteApp(rootGroup: RootGroup): Either[Rejection, RootGroup] = {
        rootGroup.app(appId) match {
          case None =>
            notFound
          case Some(app) =>
            if (authorizer.isAuthorized(identity, DeleteRunSpec, app))
              Right(rootGroup.removeApp(appId))
            else
              notAuthorized
        }
      }

      onSuccess(groupManager.updateRootEither(appId.parent, deleteApp, force = force)) {
        case Right(plan) =>
          completeWithDeploymentForApp(appId, plan)
        case Left(rej) =>
          reject(rej)
      }
    }

  private def restartApp(appId: PathId)(implicit identity: Identity): Route = {
    forceParameter { force =>

      def markForRestartingOrThrow(opt: Option[AppDefinition]): Either[Rejection, AppDefinition] =
        opt.map(Right(_)).getOrElse(Left(Rejections.EntityNotFound.noApp(appId): Rejection))
          .flatMap { checkAuthorization(UpdateRunSpec, _) }
          .map(_.markedForRestarting)

      val newVersion = clock.now()

      onSuccessLegacy(Some(appId))(
        groupManager.updateApp(
          appId,
          { app => rejectLeftViaThrow(markForRestartingOrThrow(app)) },
          newVersion, force)
      ).apply { restartDeployment =>
        completeWithDeploymentForApp(appId, restartDeployment)
      }
    }
  }

  private def listRunningTasks(appId: PathId)(implicit identity: Identity): Route = {
    val maybeApp = groupManager.app(appId)

    maybeApp.map { app =>
      authorized(ViewRunSpec, app).apply {

        val tasksF = instanceTracker.instancesBySpec flatMap { instancesBySpec =>
          runningTasks(Set(appId), instancesBySpec)
        }

        onSuccess(tasksF) { tasks =>
          complete(Json.obj("tasks" -> tasks.toRaml))
        }
      }

    } getOrElse {
      reject(Rejections.EntityNotFound.noApp(appId))
    }
  }

  private def runningTasks(appIds: Set[PathId], instancesBySpec: InstancesBySpec): Future[Set[EnrichedTask]] = {
    Source(appIds)
      .filter(instancesBySpec.hasSpecInstances)
      .mapAsync(1)(id => healthCheckManager.statuses(id).map(_ -> id))
      .mapConcat {
        case (health, id) =>
          instancesBySpec.specInstances(id).flatMap { instance =>
            instance.tasksMap.values.map { task =>
              EnrichedTask(id, task, instance.agentInfo, health.getOrElse(instance.instanceId, Nil))
            }
          }
      }
      .runWith(Sink.set)
  }

  private def killTasks(appId: PathId)(implicit identity: Identity): Route = {
    // the line below doesn't look nice but it doesn't compile if we use parameters directive
    (forceParameter & parameter("host") & parameter("scale".as[Boolean].?(false)) & parameter("wipe".as[Boolean].?(false))) {
      (force, host, scale, wipe) =>

        def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
          appTasks.filter(_.agentInfo.host == host || host == "*")
        }

        if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

        if (scale) {
          val deploymentPlanF = taskKiller.killAndScale(appId, findToKill, force)
          onSuccess(deploymentPlanF) { plan =>
            complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
          }
        } else {
          onSuccess(taskKiller.kill(appId, findToKill, wipe)) { instances =>
            complete(Json.obj("tasks" -> instances))
          }
        }
    }
  }

  private def killTask(appId: PathId, taskId: TaskId)(implicit identity: Identity): Route = {
    // the line below doesn't look nice but it doesn't compile if we use parameters directive
    (forceParameter & parameter("host") & parameter("scale".as[Boolean].?(false)) & parameter("wipe".as[Boolean].?(false))) {
      (force, host, scale, wipe) =>

        def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
          try {
            val instanceId = taskId.instanceId
            appTasks.filter(_.instanceId == instanceId)
          } catch {
            // the id can not be translated to an instanceId
            case _: MatchError => Seq.empty
          }
        }

        if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

        if (scale) {
          val deploymentPlanF = taskKiller.killAndScale(appId, findToKill, force)
          onSuccess(deploymentPlanF) { plan =>
            complete((StatusCodes.OK, List(Headers.`Marathon-Deployment-Id`(plan.id)), DeploymentResult(plan.id, plan.version.toOffsetDateTime)))
          }
        } else {
          onSuccess(taskKiller.kill(appId, findToKill, wipe)) { instances =>
            complete(Json.obj("tasks" -> instances))
          }
        }
    }
  }

  private def listVersions(appId: PathId)(implicit identity: Identity): Route = {
    val versions = groupManager.appVersions(appId).runWith(Sink.seq)
    authorized(ViewRunSpec, groupManager.app(appId), Rejections.EntityNotFound.noApp(appId)).apply {
      onSuccess(versions) { versions =>
        complete(Json.obj("versions" -> versions))
      }
    }
  }

  private def getVersion(appId: PathId, version: Timestamp)(implicit identity: Identity): Route = {
    onSuccess(groupManager.appVersion(appId, version.toOffsetDateTime)) {
      case Some(app) =>
        authorized(ViewRunSpec, app, Rejections.EntityNotFound.noApp(appId)).apply {
          complete(app)
        }
      case None =>
        reject(Rejections.EntityNotFound.noApp(appId))
    }
  }

  private def rejectLeftViaThrow[T](t: Either[Rejection, T]): T = t match {
    case Left(r) => throw RejectionError(r)
    case Right(t) => t
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

  private implicit val validateAndNormalizeAppUpdate: Normalization[raml.AppUpdate] =
    appUpdateNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())
}
