package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.{ ModelValidation, RestResource }
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

import scala.collection.immutable.Seq

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    taskFailureRepository: TaskFailureRepository,
    val config: MarathonConf,
    groupManager: GroupManager) extends RestResource with ModelValidation {

  val ListApps = """^((?:.+/)|)\*$""".r
  val EmbedTasks = "apps.tasks"
  val EmbedTasksAndFailures = "apps.failures"

  @GET
  @Timed
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String,
            @QueryParam("embed") embed: String): Map[String, Iterable[AppDefinition]] = {
    val apps = if (cmd != null || id != null) search(cmd, id) else service.listApps()
    val runningDeployments = result(service.listRunningDeployments()).map(r => r._1)
    val mapped =
      if (embed == EmbedTasks) apps.map { app =>
        app.withTasksAndDeployments(enrichedTasks(app), runningDeployments)
      }
      else if (embed == EmbedTasksAndFailures) apps.map { app =>
        app.withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )
      }
      else apps.map { app =>
        app.withTaskCountsAndDeployments(enrichedTasks(app), runningDeployments)
      }
    Map("apps" -> mapped)
  }

  @POST
  @Timed
  def create(@Context req: HttpServletRequest, app: AppDefinition,
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val baseId = app.id.canonicalPath()
    requireValid(checkApp(app, baseId.parent))
    maybePostEvent(req, app)
    val managed = app.copy(id = baseId, dependencies = app.dependencies.map(_.canonicalPath(baseId)))
    result(groupManager.updateApp(baseId, _ => managed, managed.version, force))
    Response.created(new URI(baseId.toString)).entity(managed).build()
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String): Response = {
    def runningDeployments: Seq[DeploymentPlan] = result(service.listRunningDeployments()).map(r => r._1)
    def transitiveApps(gid: PathId): Response = {
      val apps = result(groupManager.group(gid)).map(group => group.transitiveApps).getOrElse(Nil)
      val withTasks = apps.map { app =>
        app.withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )
      }
      ok(Map("*" -> withTasks))
    }
    def app(): Response = service.getApp(id.toRootPath) match {
      case Some(app) =>
        val mapped = app.withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )
        ok(Map("app" -> mapped))

      case None => unknownApp(id.toRootPath)
    }
    id match {
      case ListApps(gid) => transitiveApps(gid.toRootPath)
      case _             => app()
    }
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(@Context req: HttpServletRequest,
              @PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean,
              appUpdate: AppUpdate): Response = {
    // prefer the id from the AppUpdate over that in the UI
    val appId = appUpdate.id.map(_.canonicalPath()).getOrElse(id.toRootPath)
    // TODO error if they're not the same?
    val updateWithId = appUpdate.copy(id = Some(appId))

    requireValid(checkUpdate(updateWithId, needsId = false))

    service.getApp(appId) match {
      case Some(app) =>
        //if version is defined, replace with version
        val update = updateWithId.version.flatMap(v => service.getApp(appId, v)).orElse(Some(updateWithId(app)))
        val response = update.map { updatedApp =>
          maybePostEvent(req, updatedApp)
          val deployment = result(groupManager.updateApp(appId, _ => updatedApp, updatedApp.version, force))
          deploymentResult(deployment)
        }
        response.getOrElse(unknownApp(appId, updateWithId.version))

      case None => create(req, updateWithId(AppDefinition(appId)), force)
    }
  }

  @PUT
  @Timed
  def replaceMultiple(@DefaultValue("false")@QueryParam("force") force: Boolean,
                      updates: Seq[AppUpdate]): Response = {
    requireValid(checkUpdates(updates))
    val version = Timestamp.now()
    def updateApp(update: AppUpdate, app: AppDefinition): AppDefinition = {
      update.version.flatMap(v => service.getApp(app.id, v)).orElse(Some(update(app))).getOrElse(app)
    }
    def updateGroup(root: Group): Group = updates.foldLeft(root) { (group, update) =>
      update.id match {
        case Some(id) => group.updateApp(id.canonicalPath(), updateApp(update, _), version)
        case None     => group
      }
    }
    val deployment = result(groupManager.update(PathId.empty, updateGroup, version, force))
    deploymentResult(deployment)
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@Context req: HttpServletRequest,
             @DefaultValue("true")@QueryParam("force") force: Boolean,
             @PathParam("id") id: String): Response = {
    val appId = id.toRootPath
    service.getApp(appId) match {
      case Some(app) =>
        maybePostEvent(req, AppDefinition(id = appId))
        val deployment = result(groupManager.update(appId.parent, _.removeApplication(appId), force = force))
        deploymentResult(deployment)

      case None => unknownApp(appId)
    }
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource =
    new AppTasksResource(service, taskTracker, healthCheckManager, config, groupManager)

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, config)

  private def enrichedTasks(app: AppDefinition): Seq[EnrichedTask] =
    taskTracker.get(app.id).map { task =>
      val hcResults = result(healthCheckManager.status(app.id, task.getId))
      EnrichedTask(app.id, task, hcResults)
    }.to[Seq]

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))

  private def search(cmd: String, id: String): Iterable[AppDefinition] = {
    /* Returns true iff `a` is a prefix of `b`, case-insensitively */
    def isPrefix(a: String, b: String): Boolean =
      b.toLowerCase contains a.toLowerCase

    service.listApps().filter { app =>
      val appMatchesCmd =
        cmd != null &&
          cmd.nonEmpty &&
          app.cmd.exists(isPrefix(cmd, _))

      val appMatchesId =
        id != null &&
          id.nonEmpty && isPrefix(id, app.id.toString)

      appMatchesCmd || appMatchesId
    }
  }
}
