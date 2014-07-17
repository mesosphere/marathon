package mesosphere.marathon.api.v1

import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import org.apache.log4j.Logger

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Path("v1/apps")
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupManager: GroupManager,
    val config: MarathonConf) extends RestResource {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index = service.listApps.map { _.withTaskCounts(taskTracker) }

  @POST
  @Path("start")
  @Timed
  def start(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    val withRootId = app.copy(id = app.id.canonicalPath())
    result(groupManager.updateApp(withRootId.id, _ => withRootId))
    noContent
  }

  @POST
  @Path("stop")
  @Timed
  def stop(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    maybePostEvent(req, app)
    val appId = app.id.canonicalPath()
    result(groupManager.update(appId.parent, _.removeApplication(appId)))
    noContent
  }

  @POST
  @Path("scale")
  @Timed
  def scale(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    result(groupManager.updateApp(app.id.canonicalPath(), _.copy(instances = app.instances)))
    noContent
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) {
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))
  }

  @GET
  @Path("search")
  @Timed
  def search(@QueryParam("id") id: String,
             @QueryParam("cmd") cmd: String) = {
    service.listApps().filter { x =>
      val validId = id == null || id.isEmpty || x.id.toString.toLowerCase.contains(id.toLowerCase)
      val validCmd = cmd == null || cmd.isEmpty || x.cmd.toLowerCase.contains(cmd.toLowerCase)

      // Maybe add some other query parameters?
      validId && validCmd
    }
  }

  @GET
  @Path("{appId}/tasks")
  @Timed
  def app(@PathParam("appId") appId: String): Response = {
    val pathId = appId.toRootPath
    if (taskTracker.contains(pathId)) {
      val tasks = taskTracker.get(pathId)
      val result = Map(appId -> tasks)
      ok(result)
    }
    else {
      unknownApp(pathId)
    }
  }
}
