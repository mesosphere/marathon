package mesosphere.marathon.api.v1

import scala.collection.JavaConverters._
import javax.ws.rs._
import mesosphere.marathon.MarathonSchedulerService
import javax.ws.rs.core.{Context, Response, MediaType}
import javax.inject.{Named, Inject}
import javax.validation.Valid
import com.codahale.metrics.annotation.Timed
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{EventModule, ApiPostEvent}
import javax.servlet.http.HttpServletRequest
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.util.logging.Logger
import org.apache.mesos.Protos.TaskID

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
  def index = {
    service.listApps
  }

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
  def scale(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.scaleApp(app), service.defaultWait)
    Response.noContent.build
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) {
    if (eventBus.nonEmpty) {
      val ip = req.getRemoteAddr
      val path = req.getRequestURI
      eventBus.get.post(new ApiPostEvent(ip, path, app))
    }
  }

  @GET
  @Path("search")
  @Timed
  def search(@QueryParam("id") id: String,
             @QueryParam("cmd") cmd: String) = {
    service.listApps.filter {
      x =>
        var valid = true
        if (id != null && !id.isEmpty && !x.id.toLowerCase.contains(id.toLowerCase)) {
          valid = false
        }
        if (cmd != null && !cmd.isEmpty && !x.cmd.toLowerCase.contains(cmd.toLowerCase)) {
          valid = false
        }
        // Maybe add some other query parameters?
        valid
    }
  }

  import Implicits._

  @GET
  @Path("{appId}/tasks")
  @Timed
  def app(@PathParam("appId") appId: String): Response = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val result = Map(appId -> tasks.map(s => s: Map[String, Object]))
      Response.ok(result).build
    } else {
      Response.noContent.status(404).build
    }
  }

}
