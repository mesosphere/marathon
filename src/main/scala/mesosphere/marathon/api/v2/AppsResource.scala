package mesosphere.marathon.api.v2

import javax.ws.rs._
import scala.Array
import javax.ws.rs.core.{ Response, Context, MediaType }
import javax.inject.{ Named, Inject }
import mesosphere.marathon.event.{RestartFailed, RestartSuccess, EventModule, ApiPostEvent}
import com.google.common.eventbus.EventBus
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.marathon.tasks.TaskTracker
import com.codahale.metrics.annotation.Timed
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Await
import java.net.URI
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.api.Responses
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    config: MarathonConf) {

  @GET
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String) = {
    val apps = if (cmd != null || id != null) {
      search(cmd, id)
    }
    else {
      service.listApps()
    }

    Map("apps" -> apps.map(_.withTaskCounts(taskTracker)))
  }

  @POST
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.startApp(app), config.zkTimeoutDuration)
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
    @Valid appUpdate: AppUpdate): Response = {
    val updatedApp = appUpdate.apply(AppDefinition(id))

    service.getApp(id) match {
      case Some(app) =>
        maybePostEvent(req, updatedApp)
        Await.result(service.updateApp(id, appUpdate), config.zkTimeoutDuration)
        Response.noContent.build

      case None => create(req, updatedApp)
    }
  }

  @PUT
  @Path("restart/{id}")
  @Timed
  def restart(
    @Context req: HttpServletRequest,
    @PathParam("id") id: String,
    @QueryParam("batchSize") @DefaultValue("2") batchSize: Int
  ): Response = {
    service.getApp(id) match {
      case Some(app) =>
        service.restartApp(id, batchSize) onComplete {
          case Success(_) => eventBus.foreach(_.post(RestartSuccess(id)))
          case _ => eventBus.foreach(_.post(RestartFailed(id)))
        }
        Response.ok().build()

      case None => Responses.unknownApp(id)
    }
  }

  @DELETE
  @Path("{id}")
  @Timed
  def delete(@Context req: HttpServletRequest, @PathParam("id") id: String): Response = {
    val app = AppDefinition(id = id)
    maybePostEvent(req, app)
    Await.result(service.stopApp(app), config.zkTimeoutDuration)
    Response.noContent.build
  }

  @Path("{appId}/tasks")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def appTasksResource() = new AppTasksResource(service, taskTracker, healthCheckManager)

  @Path("{appId}/versions")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def appVersionsResource() = new AppVersionsResource(service)

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
