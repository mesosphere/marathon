package mesosphere.marathon.api.v2

import javax.ws.rs._
import scala.Array
import javax.ws.rs.core.{Response, Context, MediaType}
import javax.inject.{Named, Inject}
import mesosphere.marathon.event.EventModule
import com.google.common.eventbus.EventBus
import mesosphere.marathon.{MarathonSchedulerService, BadRequestException}
import mesosphere.marathon.tasks.TaskTracker
import com.codahale.metrics.annotation.Timed
import com.sun.jersey.api.NotFoundException
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Await
import mesosphere.marathon.event.ApiPostEvent
import java.net.URI
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.api.{PATCH, Responses}

/**
 * @author Tobi Knaup
 */

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager) {

  @GET
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String) = {
    val apps = if (cmd != null || id != null) {
      search(cmd, id)
    } else {
      service.listApps()
    }

    Map("apps" -> apps.map(_.withTaskCounts(taskTracker)))
  }

  @POST
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    validateContainerOpts(app)

    maybePostEvent(req, app)
    Await.result(service.startApp(app), service.defaultWait)
    Response.created(new URI(s"${app.id}")).build
  }

  @GET
  @Path("{id}")
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def show(@PathParam("id") id: String): Response = service.getApp(id) match {
    case Some(app) =>
      Response.ok(Map("app" -> app.withTasks(taskTracker))).build

    case None => Responses.unknownApp(id)
  }

  @PUT
  @Path("{id}")
  @Timed
  def replace(
    @Context req: HttpServletRequest,
    @PathParam("id") id: String,
    @Valid appUpdate: AppUpdate
  ): Response = {
    val updatedApp = appUpdate.apply(AppDefinition(id))

    service.getApp(id) match {
      case Some(app) =>
        validateContainerOpts(updatedApp)
        maybePostEvent(req, updatedApp)
        Await.result(service.updateApp(id, appUpdate), service.defaultWait)
        Response.noContent.build

      case None => create(req, updatedApp)
    }
  }

  @PATCH
  @Path("{id}")
  @Timed
  def update(
    @Context req: HttpServletRequest,
    @PathParam("id") id: String,
    @Valid appUpdate: AppUpdate
  ): Response = {

    val effectiveUpdate =
      if (appUpdate.version.isEmpty) appUpdate
      else {
        // lookup the old version, create an AppUpdate from it.
        service.getApp(id, appUpdate.version.get) map { appDef =>
          AppUpdate.fromAppDefinition(appDef)
        } getOrElse {
          throw new NotFoundException("Rollback version does not exist")
        }
      }

    service.getApp(id).fold(Responses.unknownApp(id)) { appDef =>
      val updatedApp = effectiveUpdate.apply(appDef)
      validateContainerOpts(updatedApp)
      maybePostEvent(req, updatedApp)
      Await.result(service.updateApp(id, effectiveUpdate), service.defaultWait)
      Response.noContent.build
    }
  }

  @DELETE
  @Path("{id}")
  @Timed
  def delete(@Context req: HttpServletRequest, @PathParam("id") id: String): Response = {
    val app = AppDefinition(id = id)
    maybePostEvent(req, app)
    Await.result(service.stopApp(app), service.defaultWait)
    Response.noContent.build
  }

  @Path("{appId}/tasks")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def appTasksResource() = new AppTasksResource(service, taskTracker, healthCheckManager)

  @Path("{appId}/versions")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def appVersionsResource() = new AppVersionsResource(service)

  /**
   * Causes a 400: Bad Request if container options are supplied
   * with the default executor
   */
  private def validateContainerOpts(app: AppDefinition): Unit =
    if (app.container.isDefined && (app.executor == "" || app.executor == "//cmd"))
      throw new BadRequestException(
        "Container options are not supported with the default executor"
      )

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.foreach(_.post(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app)))

  private def search(cmd: String, id: String): Iterable[AppDefinition] = {
    /** Returns true iff `a` is a prefix of `b`, case-insensitively */
    def isPrefix(a: String, b: String): Boolean =
      b.toLowerCase contains a.toLowerCase

    service.listApps().filter { app =>
      val appMatchesCmd = cmd != null && cmd.nonEmpty && isPrefix(cmd, app.cmd)
      val appMatchesId = id != null && id.nonEmpty && isPrefix(id, app.id)

      appMatchesCmd || appMatchesId
    }
  }
}
