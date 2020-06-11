package mesosphere.marathon
package api.v2

import java.net.URI
import java.time.Clock

import akka.event.EventStream
import com.wix.accord.Validator
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.RestResource.RestStreamingBody
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.{AuthResource, PATCH, RestResource}
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.util.RoleSettings
import org.glassfish.jersey.server.ManagedAsync
import play.api.libs.json.{JsObject, Json}

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    clock: Clock,
    eventBus: EventStream,
    appTasksRes: AppTasksResource,
    service: MarathonSchedulerService,
    appInfoService: AppInfoService,
    val config: MarathonConf,
    groupManager: GroupManager,
    pluginManager: PluginManager)(implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext) extends RestResource with AuthResource {

  import AppHelpers._
  import Normalization._

  private[this] val ListApps = """^((?:.+/)|)\*$""".r

  private def createValidatorAndNormalizerForApp(pathId: AbsolutePathId, forceRoleUpdate: Boolean): (Normalization[raml.App], Validator[AppDefinition]) = {
    val roleSettings = RoleSettings.forService(config, pathId, groupManager.rootGroup(), forceRoleUpdate)
    val normalizationConfig = AppNormalization.Configuration(config, roleSettings.defaultRole)
    val normalizer: Normalization[raml.App] = appNormalization(normalizationConfig, roleSettings.validRoles)(AppNormalization.withCanonizedIds())
    val validator: Validator[AppDefinition] = AppDefinition.validAppDefinition(config.availableFeatures, roleSettings)(pluginManager)

    (normalizer, validator)
  }

  private def createValidatorAndNormalizerForAppUpdate(pathId: AbsolutePathId, forceRoleUpdate: Boolean): (Normalization[raml.AppUpdate], Validator[AppDefinition]) = {
    val roleSettings = RoleSettings.forService(config, pathId, groupManager.rootGroup(), forceRoleUpdate)
    val normalizationConfig = AppNormalization.Configuration(config, roleSettings.defaultRole)
    val normalizer: Normalization[raml.AppUpdate] = appUpdateNormalization(normalizationConfig)(AppNormalization.withCanonizedIds())
    val validator: Validator[AppDefinition] = AppDefinition.validAppDefinition(config.availableFeatures, roleSettings)(pluginManager)

    (normalizer, validator)
  }

  @GET
  def index(
    @QueryParam("cmd") cmd: String,
    @QueryParam("id") id: String,
    @QueryParam("label") label: String,
    @QueryParam("embed") embed: java.util.Set[String],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val selector = selectAuthorized(search(Option(cmd), Option(id), Option(label)))
      // additional embeds are deprecated!
      val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.asScala.toSet) +
        AppInfo.Embed.Counts + AppInfo.Embed.Deployments
      val mapped = await(appInfoService.selectAppsBy(selector, resolvedEmbed))
      ok(raml.AppList(mapped))
    }
  }

  @POST
  @ManagedAsync
  def create(
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val ramlApp = Json.parse(body).as[raml.App]

      implicit val (normalize, validate) = createValidatorAndNormalizerForApp(PathId(ramlApp.id).canonicalPath(PathId.root), false)

      val now = clock.now()

      val app = validateOrThrow(Raml.fromRaml(ramlApp.normalize)).copy(versionInfo = VersionInfo.OnlyVersion(now))

      checkAuthorization(CreateRunSpec, app)

      def createOrThrow(opt: Option[AppDefinition]) = opt
        .map(_ => throw ConflictingChangeException(s"An app with id [${app.id}] already exists."))
        .getOrElse(app)

      val plan = await(groupManager.updateApp(app.id, createOrThrow, app.version, force))
      val appWithDeployments = raml.AppInfo.fromParent(
        parent = Raml.toRaml(app),
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksHealthy = Some(0),
        tasksUnhealthy = Some(0),
        tasks = None,
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )

      maybePostEvent(req, app)

      // servletRequest.getAsyncContext
      Response
        .created(new URI(app.id.toString))
        .header(RestResource.DeploymentHeader, plan.id)
        .entity(new RestResource.RestStreamingBody(appWithDeployments))
        .build()
    }
  }

  @GET
  @Path("""{id:.+}""")
  def show(
    @PathParam("id") id: String,
    @QueryParam("embed") embed: java.util.Set[String],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.asScala.toSet) ++ Set(
        // deprecated. For compatibility.
        AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
      )

      id match {
        case ListApps(gid) =>
          val groupId = gid.toAbsolutePath
          groupManager.group(groupId) match {
            case Some(group) =>
              checkAuthorization(ViewGroup, group)
              val appsWithTasks = await(appInfoService.selectAppsInGroup(groupId, authzSelector, resolvedEmbed))
              Response.ok(new RestStreamingBody(Map("*" -> appsWithTasks))).build()
            case None =>
              unknownGroup(groupId)
          }
        case _ =>
          val appId = id.toAbsolutePath

          val appInfo = await(appInfoService.selectApp(appId, authzSelector, resolvedEmbed))
          val appDef = groupManager.app(appId)

          (appInfo, appDef) match {
            case (Some(info), Some(app)) =>
              checkAuthorization(ViewRunSpec, app)
              Response.ok(new RestStreamingBody(Map("app" -> info))).build()
            case _ => unknownApp(appId)
          }
      }
    }
  }

  /**
    * Validate and normalize a single application update submitted via the REST API. Validation exceptions are not
    * handled here, that's left as an exercise for the caller.
    *
    * @param appId used as the id of the generated app update (vs. whatever might be in the JSON body)
    * @param body is the raw, unparsed JSON
    * @param updateType CompleteReplacement if we want to replace the app entirely, PartialUpdate if we only want to update provided parts
    */
  def canonicalAppUpdateFromJson(appId: AbsolutePathId, body: Array[Byte], updateType: UpdateType, forceRoleUpdate: Boolean): raml.AppUpdate = {
    val roleSettings = RoleSettings.forService(config, appId, groupManager.rootGroup(), forceRoleUpdate)
    val normalizationConfig = AppNormalization.Configuration(config, roleSettings.defaultRole)

    implicit val normalizerApp: Normalization[raml.App] = appNormalization(normalizationConfig, roleSettings.validRoles)(AppNormalization.withCanonizedIds())
    implicit val normalizerUpdate: Normalization[raml.AppUpdate] = appUpdateNormalization(normalizationConfig)(AppNormalization.withCanonizedIds())

    updateType match {
      case CompleteReplacement =>
        // this is a complete replacement of the app as we know it, so parse and normalize as if we're dealing
        // with a brand new app because the rules are different (for example, many fields are non-optional with brand-new apps).
        // however since this is an update, the user isn't required to specify an ID as part of the definition so we do
        // some hackery here to pass initial JSON parsing.
        val jsObj = Json.parse(body).as[JsObject] + ("id" -> Json.toJson(appId.toString))

        // the version is thrown away in conversion to AppUpdate
        jsObj.as[raml.App].normalize.toRaml[raml.AppUpdate]

      case PartialUpdate(existingApp) =>
        import mesosphere.marathon.raml.AppConversion.appUpdateRamlReader

        val appUpdate = Json.parse(body).as[raml.AppUpdate].normalize
        Raml.fromRaml(appUpdate -> existingApp)(appUpdateRamlReader).normalize //validate if the resulting app is correct
        appUpdate.copy(id = Some(appId.toString))

    }
  }

  /**
    * Validate and normalize an array of application updates submitted via the REST API. Validation exceptions are not
    * handled here, that's left as an exercise for the caller.
    *
    * @param body is the raw, unparsed JSON
    * @param partialUpdate true if the JSON should be parsed as a partial application update (all fields optional)
    *                      or as a wholesale replacement (parsed like an app definition would be)
    */
  def canonicalAppUpdatesFromJson(basePath: AbsolutePathId, body: Array[Byte], partialUpdate: Boolean, forceRoleUpdate: Boolean): Seq[raml.AppUpdate] = {
    if (partialUpdate) {
      Json.parse(body).as[Seq[raml.AppUpdate]].map { appUpdate =>
        require(appUpdate.id.isDefined, "App Update must have app id set")

        implicit val (normalizer, _) = createValidatorAndNormalizerForAppUpdate(PathId(appUpdate.id.get).canonicalPath(basePath), forceRoleUpdate)
        appUpdate.normalize
      }
    } else {
      // this is a complete replacement of the app as we know it, so parse and normalize as if we're dealing
      // with a brand new app because the rules are different (for example, many fields are non-optional with brand-new apps).
      // the version is thrown away in toUpdate so just pass `zero` for now.
      Json.parse(body).as[Seq[raml.App]].map { app =>

        implicit val (normalizer, _) = createValidatorAndNormalizerForApp(app.id.toPath.canonicalPath(basePath), forceRoleUpdate)
        app.normalize.toRaml[raml.AppUpdate]
      }
    }
  }

  @PUT
  @Path("""{id:.+}""")
  def replace(
    @PathParam("id") id: String,
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @DefaultValue("true")@QueryParam("partialUpdate") partialUpdate: Boolean,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      await(update(id, body, force, partialUpdate, req, allowCreation = true))
    }
  }

  @PATCH
  @Path("""{id:.+}""")
  def patch(
    @PathParam("id") id: String,
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      await(update(id, body, force, partialUpdate = true, req, allowCreation = false))
    }
  }

  @PUT
  def replaceMultiple(
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @DefaultValue("true")@QueryParam("partialUpdate") partialUpdate: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      await(updateMultiple(PathId.root, force, partialUpdate, body, allowCreation = true, forceRoleUpdate = force))
    }
  }

  @PATCH
  def patchMultiple(
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    body: Array[Byte],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      await(updateMultiple(PathId.root, force, partialUpdate = true, body, allowCreation = false, forceRoleUpdate = force))
    }
  }

  @DELETE
  @Path("""{id:.+}""")
  def delete(
    @DefaultValue("true")@QueryParam("force") force: Boolean,
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val appId = id.toAbsolutePath
      val version = Timestamp.now()

      def deleteApp(rootGroup: RootGroup) = {
        checkAuthorization(DeleteRunSpec, rootGroup.app(appId), AppNotFoundException(appId))
        rootGroup.removeApp(appId, version)
      }

      deploymentResult(await(groupManager.updateRoot(appId.parent, deleteApp, version = version, force = force)))
    }
  }
  @DELETE
  @Path("""{id:.+}/restart""")
  def deleteRestart(
    @DefaultValue("true")@QueryParam("force") force: Boolean,
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = delete(force, id + "/restart", req, asyncResponse)

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource = appTasksRes

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, groupManager, authenticator,
    authorizer, config)

  @POST
  @Path("{id:.+}/restart")
  def restart(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val appId = id.toAbsolutePath

      def markForRestartingOrThrow(opt: Option[AppDefinition]) = {
        opt
          .map(checkAuthorization(UpdateRunSpec, _))
          .map(_.markedForRestarting)
          .getOrElse(throw AppNotFoundException(appId))
      }

      val newVersion = clock.now()
      val restartDeployment = await(
        groupManager.updateApp(id.toAbsolutePath, markForRestartingOrThrow, newVersion, force)
      )

      deploymentResult(restartDeployment)
    }
  }

  /**
    * Internal representation of `replace or update` logic.
    *
    * @param id appId
    * @param body request body
    * @param force force update?
    * @param partialUpdate partial update?
    * @param req http servlet request
    * @param allowCreation is creation allowed?
    * @param identity implicit identity
    * @return http servlet response
    */
  private[this] def update(id: String, body: Array[Byte], force: Boolean, partialUpdate: Boolean,
    req: HttpServletRequest, allowCreation: Boolean)(implicit identity: Identity): Future[Response] = async {
    val appId = id.toAbsolutePath

    // can lead to race condition where two non-existent apps with the same id are inserted concurrently,
    // one of them will be overwritten by another
    val maybeExistingApp = groupManager.app(appId)

    val updateType = (maybeExistingApp, partialUpdate) match {
      case (None, _) => CompleteReplacement
      case (Some(app), true) => PartialUpdate(app)
      case (_, false) => CompleteReplacement
    }

    implicit val (normalizer, validate) = createValidatorAndNormalizerForApp(appId, force)

    val appUpdate = canonicalAppUpdateFromJson(appId, body, updateType, force)

    val version = clock.now()
    val plan = await(groupManager.updateApp(appId, AppHelpers.updateOrCreate(appId, _, appUpdate, partialUpdate, allowCreation, clock.now(), service), version, force))
    val response = plan.original.app(appId)
      .map(_ => Response.ok())
      .getOrElse(Response.created(new URI(appId.toString)))
    plan.target.app(appId).foreach { appDef =>
      maybePostEvent(req, appDef)
    }
    deploymentResult(plan, response)
  }

  /**
    * Internal representation of `replace or update` logic for multiple apps.
    *
    * @param forceDeployment force update?
    * @param forceRoleUpdate force role update?
    * @param partialUpdate partial update?
    * @param body request body
    * @param allowCreation is creation allowed?
    * @param identity implicit identity
    * @return http servlet response
    */
  private[this] def updateMultiple(basePath: AbsolutePathId, forceDeployment: Boolean, partialUpdate: Boolean,
    body: Array[Byte], allowCreation: Boolean, forceRoleUpdate: Boolean)(implicit identity: Identity): Future[Response] = async {

    val version = clock.now()
    val updates = canonicalAppUpdatesFromJson(basePath, body, partialUpdate, forceRoleUpdate)

    def updateGroup(rootGroup: RootGroup): RootGroup = updates.foldLeft(rootGroup) { (group, update) =>
      update.id.map(_.toPath.canonicalPath(basePath)) match {
        case Some(id) => {
          implicit val (normalizer, validate) = createValidatorAndNormalizerForApp(id, forceRoleUpdate)

          group.updateApp(id, AppHelpers.updateOrCreate(id, _, update, partialUpdate, allowCreation = allowCreation, clock.now(), service), version)
        }
        case None => group
      }
    }

    deploymentResult(await(groupManager.updateRoot(PathId.root, updateGroup, version, forceDeployment)))
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))

  private[v2] def search(cmd: Option[String], id: Option[String], label: Option[String]): AppSelector = {
    def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase
    val selectors = Seq[Option[Selector[AppDefinition]]](
      cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
      id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
      label.map(new LabelSelectorParsers().parsed)
    ).flatten
    Selector.forall(selectors)
  }

  def selectAuthorized(fn: => AppSelector)(implicit identity: Identity): AppSelector = {
    Selector.forall(Seq(authzSelector, fn))
  }
}

sealed trait UpdateType
case object CompleteReplacement extends UpdateType
case class PartialUpdate(existingApp: AppDefinition) extends UpdateType