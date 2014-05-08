package mesosphere.marathon.api.v1

import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.event.{EventModule, ApiPostEvent}
import javax.ws.rs._
import javax.ws.rs.core.{Context, Response, MediaType}
import javax.inject.{Named, Inject}
import javax.validation.Valid
import javax.servlet.http.HttpServletRequest
import com.codahale.metrics.annotation.Timed
import com.google.common.eventbus.EventBus
import scala.concurrent.Await
import org.apache.log4j.Logger
import mesosphere.marathon.api.Responses

/**
 * @author Tobi Knaup
 */
@Path("v1/apps")
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index = service.listApps.map { _.withTaskCounts(taskTracker) }

  @POST
  @Path("start")
  @Timed
  def start(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.startApp(app), service.defaultWait)
    Response.noContent.build
  }

  @POST
  @Path("stop")
  @Timed
  def stop(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.stopApp(app), service.defaultWait)
    Response.noContent.build
  }

  @POST
  @Path("scale")
  @Timed
  def scale(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    val appUpdate = AppUpdate(instances = Some(app.instances))
    Await.result(service.updateApp(app.id, appUpdate), service.defaultWait)
    Response.noContent.build
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) {
    eventBus.foreach(_.post(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app)))
  }

  @GET
  @Path("search")
  @Timed
  def search(@QueryParam("id") id: String,
             @QueryParam("cmd") cmd: String) = {
    service.listApps.filter { x =>
      val validId = id == null || id.isEmpty || x.id.toLowerCase.contains(id.toLowerCase)
      val validCmd = cmd == null || cmd.isEmpty || x.cmd.toLowerCase.contains(cmd.toLowerCase)

      // Maybe add some other query parameters?
      validId && validCmd
    }
  }

  @GET
  @Path("{appId}/tasks")
  @Timed
  def app(@PathParam("appId") appId: String): Response = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val result = Map(appId -> tasks)
      Response.ok(result).build
    } else {
      Responses.unknownApp(appId)
    }
  }

}
