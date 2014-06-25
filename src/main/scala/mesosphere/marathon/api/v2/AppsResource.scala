package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.{Inject, Named}
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.Responses
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event.{ApiPostEvent, EventModule}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{MarathonConf, MarathonSchedulerService}

import scala.concurrent.{Await, Awaitable}

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    config: MarathonConf,
    groupManager: GroupManager) {

  @GET
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String) = {
    val apps = if (cmd != null || id != null) search(cmd, id) else service.listApps()
    Map("apps" -> apps.map(_.withTaskCounts(taskTracker)))
  }

  @POST
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    val withRootId = app.copy(id = app.id.canonicalPath())
    result(groupManager.updateApp(withRootId.id, _ => withRootId, app.version))
    Response.created(new URI(s"${withRootId.id}")).build
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def show(@PathParam("id") id: String): Response = service.getApp(id.toRootPath) match {
    case Some(app) => Response.ok(Map("app" -> app.withTasks(taskTracker))).build
    case None      => Responses.unknownApp(id.toRootPath)
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(
    @Context req: HttpServletRequest,
    @PathParam("id") id: String,
    @Valid appUpdate: AppUpdate): Response = {
    val appId = id.toRootPath

    service.getApp(appId) match {
      case Some(app) =>
        //if version is defined, replace with version
        val update = appUpdate.version.flatMap(v => service.getApp(appId, v)).orElse(Some(appUpdate(app)))
        val response = update.map { updatedApp =>
          maybePostEvent(req, updatedApp)
          result(groupManager.updateApp(appId, _ => updatedApp, updatedApp.version))
          Response.noContent.build
        }
        response.getOrElse(Responses.unknownApp(appId, appUpdate.version))

      case None => create(req, appUpdate(AppDefinition(appId)))
    }
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
  @Produces(Array(MediaType.APPLICATION_JSON))
  def appTasksResource() = new AppTasksResource(service, taskTracker, healthCheckManager)

  @Path("{appId:.+}/versions")
  @Produces(Array(MediaType.APPLICATION_JSON))
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
