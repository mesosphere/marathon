package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.Responses
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

import scala.concurrent.{ Await, Awaitable }

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    config: MarathonConf,
    groupManager: GroupManager) extends ModelValidation {

  val ListApps = """^((?:.+/)|)\*$""".r

  @GET
  @Timed
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String) = {
    val apps = if (cmd != null || id != null) search(cmd, id) else service.listApps()
    Map("apps" -> apps.map(_.withTaskCounts(taskTracker)))
  }

  @POST
  @Timed
  def create(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    val baseId = app.id.canonicalPath()
    requireValid(checkApp(app, baseId.parent))
    maybePostEvent(req, app)
    result(groupManager.updateApp(baseId, _ => app.copy(id = baseId), app.version))
    Response.created(new URI(s"$baseId")).build
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String): Response = {
    def transitiveApps(gid: PathId) = {
      val apps = result(groupManager.group(gid)).map(group => group.transitiveApps).getOrElse(Nil)
      val withTasks = apps.map(_.withTasks(taskTracker))
      Response.ok(Map("*" -> withTasks)).build()
    }
    def app() = service.getApp(id.toRootPath) match {
      case Some(app) => Response.ok(Map("app" -> app.withTasks(taskTracker))).build
      case None      => Responses.unknownApp(id.toRootPath)
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
              appUpdate: AppUpdate): Response = {

    val appId = appUpdate.id.map(_.canonicalPath(id.toRootPath)).getOrElse(id.toRootPath)
    val updateWithId = appUpdate.copy(id = Some(appId))

    requireValid(checkUpdate(updateWithId, needsId = false))

    service.getApp(appId) match {
      case Some(app) =>
        //if version is defined, replace with version
        val update = updateWithId.version.flatMap(v => service.getApp(appId, v)).orElse(Some(updateWithId(app)))
        val response = update.map { updatedApp =>
          maybePostEvent(req, updatedApp)
          result(groupManager.updateApp(appId, _ => updatedApp, updatedApp.version))
          Response.noContent.build
        }
        response.getOrElse(Responses.unknownApp(appId, updateWithId.version))

      case None => create(req, updateWithId(AppDefinition(appId)))
    }
  }

  @PUT
  @Timed
  def replaceMultiple(updates: Seq[AppUpdate]): Response = {
    requireValid(checkUpdates(updates))
    val version = Timestamp.now()
    def updateApp(update: AppUpdate, app: AppDefinition): AppDefinition = {
      update.version.flatMap(v => service.getApp(app.id, v)).orElse(Some(update(app))).getOrElse(app)
    }
    def updateGroup(root: Group) = updates.foldLeft(root) { (group, update) =>
      update.id match {
        case Some(id) => group.updateApp(id.canonicalPath(), updateApp(update, _), version)
        case None     => group
      }
    }
    result(groupManager.update(PathId.empty, updateGroup, version))
    Response.noContent.build
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@Context req: HttpServletRequest, @PathParam("id") id: String): Response = {
    val appId = id.toRootPath
    maybePostEvent(req, AppDefinition(id = appId))
    result(groupManager.update(appId.parent, _.removeApplication(appId)))
    Response.noContent.build
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource() = new AppTasksResource(service, taskTracker, healthCheckManager, config, groupManager)

  @Path("{appId:.+}/versions")
  def appVersionsResource() = new AppVersionsResource(service)

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)

  private def search(cmd: String, id: String): Iterable[AppDefinition] = {
    /** Returns true iff `a` is a prefix of `b`, case-insensitively */
    def isPrefix(a: String, b: String): Boolean =
      b.toLowerCase contains a.toLowerCase

    service.listApps().filter { app =>
      val appMatchesCmd = cmd != null && cmd.nonEmpty && isPrefix(cmd, app.cmd)
      val appMatchesId = id != null && id.nonEmpty && isPrefix(id, app.id.toString)

      appMatchesCmd || appMatchesId
    }
  }
}
