package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource }
import mesosphere.marathon.core.appinfo.{ AppInfoService, AppSelector, TaskCounts }
import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.{ ConflictingChangeException, MarathonConf, MarathonSchedulerService, UnknownAppException }
import play.api.libs.json.Json
import scala.collection.JavaConverters._

import scala.collection.immutable.Seq

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class AppsResource @Inject() (
    clock: Clock,
    @Named(EventModule.busName) eventBus: EventStream,
    appTasksRes: AppTasksResource,
    service: MarathonSchedulerService,
    appInfoService: AppInfoService,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    groupManager: GroupManager) extends RestResource with AuthResource {

  private[this] val ListApps = """^((?:.+/)|)\*$""".r

  @GET
  @Timed
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String,
            @QueryParam("label") label: String,
            @QueryParam("embed") embed: java.util.Set[String],
            @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val selector = selectAuthorized(search(Option(cmd), Option(id), Option(label)))
    // additional embeds are deprecated!
    val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.asScala.toSet) +
      AppInfo.Embed.Counts + AppInfo.Embed.Deployments
    val mapped = result(appInfoService.selectAppsBy(selector, resolvedEmbed))
    Response.ok(jsonObjString("apps" -> mapped)).build()
  }

  @POST
  @Timed
  def create(body: Array[Byte],
             @DefaultValue("false")@QueryParam("force") force: Boolean,
             @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withValid(Json.parse(body).as[AppDefinition].withCanonizedIds()) { appDef =>
      val now = clock.now()
      val app = appDef.copy(versionInfo = AppDefinition.VersionInfo.OnlyVersion(now))

      checkAuthorization(CreateApp, app)

      def createOrThrow(opt: Option[AppDefinition]) = opt
        .map(_ => throw new ConflictingChangeException(s"An app with id [${app.id}] already exists."))
        .getOrElse(app)

      val plan = result(groupManager.updateApp(app.id, createOrThrow, app.version, force))

      val appWithDeployments = AppInfo(
        app,
        maybeCounts = Some(TaskCounts.zero),
        maybeTasks = Some(Seq.empty),
        maybeDeployments = Some(Seq(Identifiable(plan.id)))
      )

      maybePostEvent(req, appWithDeployments.app)
      Response
        .created(new URI(app.id.toString))
        .entity(jsonString(appWithDeployments))
        .build()
    }
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String,
           @QueryParam("embed") embed: java.util.Set[String],
           @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.asScala.toSet) ++ Set(
      // deprecated. For compatibility.
      AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
    )

    def transitiveApps(groupId: PathId): Response = {
      result(groupManager.group(groupId)) match {
        case Some(group) =>
          checkAuthorization(ViewGroup, group)
          val appsWithTasks = result(appInfoService.selectAppsInGroup(groupId, allAuthorized, resolvedEmbed))
          ok(jsonObjString("*" -> appsWithTasks))
        case None =>
          unknownGroup(groupId)
      }
    }

    def app(appId: PathId): Response = {
      result(appInfoService.selectApp(appId, allAuthorized, resolvedEmbed)) match {
        case Some(appInfo) =>
          checkAuthorization(ViewApp, appInfo.app)
          ok(jsonObjString("app" -> appInfo))
        case None => unknownApp(appId)
      }
    }

    id match {
      case ListApps(gid) => transitiveApps(gid.toRootPath)
      case _             => app(id.toRootPath)
    }
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(
    @PathParam("id") id: String,
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val appId = id.toRootPath
    val now = clock.now()

    withValid(Json.parse(body).as[AppUpdate].copy(id = Some(appId))) { appUpdate =>
      val plan = result(groupManager.updateApp(appId, updateOrCreate(appId, _, appUpdate, now), now, force))

      val response = plan.original.app(appId)
        .map(_ => Response.ok())
        .getOrElse(Response.created(new URI(appId.toString)))
      maybePostEvent(req, plan.target.app(appId).get)
      deploymentResult(plan, response)
    }
  }

  @PUT
  @Timed
  def replaceMultiple(@DefaultValue("false")@QueryParam("force") force: Boolean,
                      body: Array[Byte],
                      @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withValid(Json.parse(body).as[Seq[AppUpdate]].map(_.withCanonizedIds())) { updates =>
      val version = clock.now()

      def updateGroup(root: Group): Group = updates.foldLeft(root) { (group, update) =>
        update.id match {
          case Some(id) => group.updateApp(id, updateOrCreate(id, _, update, version), version)
          case None     => group
        }
      }

      deploymentResult(result(groupManager.update(PathId.empty, updateGroup, version, force)))
    }
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@DefaultValue("true")@QueryParam("force") force: Boolean,
             @PathParam("id") id: String,
             @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val appId = id.toRootPath

    def deleteAppFromGroup(group: Group) = {
      checkAuthorization(DeleteApp, group.app(appId), UnknownAppException(appId))
      group.removeApplication(appId)
    }

    deploymentResult(result(groupManager.update(appId.parent, deleteAppFromGroup, force = force)))
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource = appTasksRes

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, groupManager, authenticator,
    authorizer, config)

  @POST
  @Path("{id:.+}/restart")
  def restart(@PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean,
              @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val appId = id.toRootPath

    def markForRestartingOrThrow(opt: Option[AppDefinition]) = {
      opt
        .map(checkAuthorization(UpdateApp, _))
        .map(_.markedForRestarting)
        .getOrElse(throw UnknownAppException(appId))
    }

    val newVersion = clock.now()
    val restartDeployment = result(
      groupManager.updateApp(id.toRootPath, markForRestartingOrThrow, newVersion, force)
    )

    deploymentResult(restartDeployment)
  }

  private def updateOrCreate(appId: PathId,
                             existing: Option[AppDefinition],
                             appUpdate: AppUpdate,
                             newVersion: Timestamp)(implicit identity: Identity): AppDefinition = {
    def createApp(): AppDefinition = {
      val app = validateOrThrow(appUpdate.empty(appId))
      checkAuthorization(CreateApp, app)
    }

    def updateApp(current: AppDefinition): AppDefinition = {
      val app = validateOrThrow(appUpdate(current))
      checkAuthorization(UpdateApp, app)
    }

    def rollback(current: AppDefinition, version: Timestamp): AppDefinition = {
      val app = service.getApp(appId, version).getOrElse(throw UnknownAppException(appId))
      checkAuthorization(ViewApp, app)
      checkAuthorization(UpdateApp, current)
      app
    }

    def updateOrRollback(current: AppDefinition): AppDefinition = appUpdate.version
      .map(rollback(current, _))
      .getOrElse(updateApp(current))

    existing match {
      case Some(app) =>
        // we can only rollback existing apps because we deleted all old versions when dropping an app
        updateOrRollback(app)
      case None =>
        createApp()
    }
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))

  private[v2] def search(cmd: Option[String], id: Option[String], label: Option[String]): AppSelector = {
    def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase
    val selectors = Seq[Option[AppSelector]](
      cmd.map(c => AppSelector(_.cmd.exists(containCaseInsensitive(c, _)))),
      id.map(s => AppSelector(app => containCaseInsensitive(s, app.id.toString))),
      label.map(new LabelSelectorParsers().parsed)
    ).flatten
    AppSelector.forall(selectors)
  }

  def allAuthorized(implicit identity: Identity): AppSelector = new AppSelector {
    override def matches(app: AppDefinition): Boolean = isAuthorized(ViewApp, app)
  }

  def selectAuthorized(fn: => AppSelector)(implicit identity: Identity): AppSelector = {
    val authSelector = new AppSelector {
      override def matches(app: AppDefinition): Boolean = isAuthorized(ViewApp, app)
    }
    AppSelector.forall(Seq(authSelector, fn))
  }
}
