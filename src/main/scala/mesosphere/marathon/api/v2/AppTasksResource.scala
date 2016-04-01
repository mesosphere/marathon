package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ GroupManager, PathId }
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSchedulerService, UnknownAppException }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class AppTasksResource @Inject() (service: MarathonSchedulerService,
                                  taskTracker: TaskTracker,
                                  taskKiller: TaskKiller,
                                  healthCheckManager: HealthCheckManager,
                                  val config: MarathonConf,
                                  groupManager: GroupManager,
                                  val authorizer: Authorizer,
                                  val authenticator: Authenticator) extends AuthResource {

  val log = LoggerFactory.getLogger(getClass.getName)
  val GroupTasks = """^((?:.+/)|)\*$""".r

  @GET
  @Timed
  def indexJson(@PathParam("appId") id: String,
                @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val taskMap = taskTracker.tasksByAppSync

    def runningTasks(appIds: Set[PathId]): Set[EnrichedTask] = for {
      runningApps <- appIds.filter(taskMap.hasAppTasks)
      id <- appIds
      health = result(healthCheckManager.statuses(id))
      task <- taskMap.appTasks(id)
    } yield EnrichedTask(id, task, health.getOrElse(task.taskId, Nil))

    id match {
      case GroupTasks(gid) =>
        val groupPath = gid.toRootPath
        val maybeGroup = result(groupManager.group(groupPath))
        withAuthorization(ViewGroup, maybeGroup, unknownGroup(groupPath)) { group =>
          ok(jsonObjString("tasks" -> runningTasks(group.transitiveApps.map(_.id))))
        }
      case _ =>
        val appId = id.toRootPath
        val maybeApp = result(groupManager.app(appId))
        withAuthorization(ViewApp, maybeApp, unknownApp(appId)) { _ =>
          ok(jsonObjString("tasks" -> runningTasks(Set(appId))))
        }
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String,
               @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val id = appId.toRootPath
    withAuthorization(ViewApp, result(groupManager.app(id)), unknownApp(id)) { app =>
      ok(EndpointsHelper.appsToEndpointString(taskTracker, Seq(app), "\t"))
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
                 @QueryParam("force")@DefaultValue("false") force: Boolean = false,
                 @QueryParam("wipe")@DefaultValue("false") wipe: Boolean = false,
                 @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val pathId = appId.toRootPath

    def findToKill(appTasks: Iterable[Task]): Iterable[Task] = {
      Option(host).fold(appTasks) { hostname =>
        appTasks.filter(_.agentInfo.host == hostname || hostname == "*")
      }
    }

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    if (scale) {
      val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
      deploymentResult(result(deploymentF))
    }
    else {
      reqToResponse(taskKiller.kill(pathId, findToKill, wipe)) {
        tasks => ok(jsonObjString("tasks" -> tasks))
      }
    }
  }

  @DELETE
  @Path("{taskId}")
  @Timed
  def deleteOne(@PathParam("appId") appId: String,
                @PathParam("taskId") id: String,
                @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
                @QueryParam("force")@DefaultValue("false") force: Boolean = false,
                @QueryParam("wipe")@DefaultValue("false") wipe: Boolean = false,
                @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val pathId = appId.toRootPath
    def findToKill(appTasks: Iterable[Task]): Iterable[Task] = appTasks.find(_.taskId == Task.Id(id))

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    if (scale) {
      val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
      deploymentResult(result(deploymentF))
    }
    else {
      reqToResponse(taskKiller.kill(pathId, findToKill, wipe)) {
        tasks => tasks.headOption.fold(unknownTask(id))(task => ok(jsonObjString("task" -> task)))
      }
    }
  }

  private def reqToResponse(
    future: Future[Iterable[Task]])(toResponse: Iterable[Task] => Response): Response = {

    import scala.concurrent.ExecutionContext.Implicits.global
    val response = future.map { tasks =>
      toResponse(tasks)
    } recover {
      case UnknownAppException(appId, version) => unknownApp(appId, version)
    }
    result(response)
  }

}
