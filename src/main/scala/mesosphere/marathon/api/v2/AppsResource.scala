package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2AppUpdate }
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.core.appinfo.{ AppInfo, AppInfoService, AppSelector, TaskCounts }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.{ ConflictingChangeException, MarathonConf, MarathonSchedulerService, UnknownAppException }
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.Future

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
    groupManager: GroupManager) extends RestResource {

  import mesosphere.util.ThreadPoolContext.context

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val ListApps = """^((?:.+/)|)\*$""".r

  @GET
  @Timed
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String,
            @QueryParam("label") label: String,
            @QueryParam("embed") embed: java.util.Set[String]): String = {
    val selector = search(Option(cmd), Option(id), Option(label))
    // additional embeds are deprecated!
    val resolvedEmbed = AppInfoEmbedResolver.resolve(embed) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
    val mapped = result(appInfoService.queryAll(selector, resolvedEmbed))

    jsonObjString("apps" -> mapped)
  }

  @POST
  @Timed
  def create(@Context req: HttpServletRequest, body: Array[Byte],
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {

    val now = clock.now()
    val app =
      validateApp(Json.parse(body).as[V2AppDefinition].withCanonizedIds().toAppDefinition)
        .copy(versionInfo = AppDefinition.VersionInfo.OnlyVersion(now))

    def createOrThrow(opt: Option[AppDefinition]) = opt
      .map(_ => throw new ConflictingChangeException(s"An app with id [${app.id}] already exists."))
      .getOrElse(app)

    val plan = result(groupManager.updateApp(app.id, createOrThrow, version = app.version, force))

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

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String, @QueryParam("embed") embed: java.util.Set[String]): Response = {
    val resolvedEmbed = AppInfoEmbedResolver.resolve(embed) ++ Set(
      // deprecated. For compatability.
      AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
    )
    def transitiveApps(gid: PathId): Response = {
      val withTasks = result(appInfoService.queryAllInGroup(gid, resolvedEmbed))
      ok(jsonObjString("*" -> withTasks))
    }
    def app(): Future[Response] = {
      val maybeAppInfo = appInfoService.queryForAppId(id.toRootPath, resolvedEmbed)

      maybeAppInfo.map {
        case Some(appInfo) => ok(jsonObjString("app" -> appInfo))
        case None          => unknownApp(id.toRootPath)
      }
    }
    id match {
      case ListApps(gid) => transitiveApps(gid.toRootPath)
      case _             => result(app())
    }
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(@Context req: HttpServletRequest,
              @PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean,
              body: Array[Byte]): Response = {
    val appId = id.toRootPath
    val appUpdate = Json.parse(body).as[V2AppUpdate].copy(id = Some(appId))
    BeanValidation.requireValid(ModelValidation.checkUpdate(appUpdate, needsId = false))

    val newVersion = clock.now()
    val plan = result(groupManager.updateApp(appId, updateOrCreate(appId, _, appUpdate, newVersion), newVersion, force))

    val response = plan.original.app(appId).map(_ => Response.ok()).getOrElse(Response.created(new URI(appId.toString)))
    maybePostEvent(req, plan.target.app(appId).get)
    deploymentResult(plan, response)
  }

  @PUT
  @Timed
  def replaceMultiple(@DefaultValue("false")@QueryParam("force") force: Boolean, body: Array[Byte]): Response = {
    val updates = Json.parse(body).as[Seq[V2AppUpdate]].map(_.withCanonizedIds())
    BeanValidation.requireValid(ModelValidation.checkUpdates(updates))
    val version = clock.now()
    def updateGroup(root: Group): Group = updates.foldLeft(root) { (group, update) =>
      update.id match {
        case Some(id) => group.updateApp(id, updateOrCreate(id, _, update, version), version)
        case None     => group
      }
    }
    deploymentResult(result(groupManager.update(PathId.empty, updateGroup, version, force)))
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@Context req: HttpServletRequest,
             @DefaultValue("true")@QueryParam("force") force: Boolean,
             @PathParam("id") id: String): Response = {
    val appId = id.toRootPath

    def deleteApp(group: Group) = group.app(appId)
      .map(_ => group.removeApplication(appId))
      .getOrElse(throw new UnknownAppException(appId))

    deploymentResult(result(groupManager.update(appId.parent, deleteApp, force = force)))
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource = appTasksRes

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, config)

  @POST
  @Path("{id:.+}/restart")
  def restart(@PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val appId = id.toRootPath

    def markForRestartingOrThrow(opt: Option[AppDefinition]) = opt
      .map(_.markedForRestarting)
      .getOrElse(throw new UnknownAppException(appId))

    val newVersion = clock.now()
    val restartDeployment = result(groupManager.updateApp(id.toRootPath, markForRestartingOrThrow, newVersion, force))

    deploymentResult(restartDeployment)
  }

  private def updateOrCreate(appId: PathId,
                             existing: Option[AppDefinition],
                             appUpdate: V2AppUpdate,
                             newVersion: Timestamp): AppDefinition = {
    def createApp() = validateApp(appUpdate(AppDefinition(appId)))
    def updateApp(current: AppDefinition) = validateApp(appUpdate(current))
    def rollback(version: Timestamp) = service.getApp(appId, version).getOrElse(throw new UnknownAppException(appId))
    def updateOrRollback(current: AppDefinition) = appUpdate.version.map(rollback).getOrElse(updateApp(current))

    existing match {
      case Some(app) =>
        // we can only rollback existing apps because we deleted all old versions when dropping an app
        updateOrRollback(app)
      case None =>
        createApp()
    }
  }

  private def validateApp(app: AppDefinition): AppDefinition = {
    BeanValidation.requireValid(ModelValidation.checkAppConstraints(V2AppDefinition(app), app.id.parent))
    val conflicts = ModelValidation.checkAppConflicts(app, result(groupManager.rootGroup()))
    if (conflicts.nonEmpty) throw new ConflictingChangeException(conflicts.mkString(","))
    app
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
}
