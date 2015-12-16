package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}

import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{GroupManager, PathId}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{MarathonConf, MarathonSchedulerService, UnknownAppException}
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
  def indexJson(@PathParam("appId") appId: String,
                @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthorized(req, resp, ViewAppOrGroup, appId.toRootPath) { implicit principal =>
      def tasks(appIds: Set[PathId]): Set[EnrichedTask] = for {
        id <- appIds
        health = result(healthCheckManager.statuses(id))
        task <- taskTracker.getTasks(id)
      } yield EnrichedTask(id, task, health.getOrElse(task.getId, Nil))

      val matchingApps = appId match {
        case GroupTasks(gid) =>
          result(groupManager.group(gid.toRootPath))
            .map(_.transitiveApps.map(_.id))
            .getOrElse(Set.empty)
        case _ => Set(appId.toRootPath)
      }

      val running = matchingApps.filter(taskTracker.contains)

      if (running.isEmpty) unknownApp(appId.toRootPath) else ok(jsonObjString("tasks" -> tasks(running)))
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String,
               @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthorized(req, resp, ViewAppOrGroup, appId.toRootPath) { implicit principal =>
      val id = appId.toRootPath
      service.getApp(id).fold(unknownApp(id)) { app =>
        ok(EndpointsHelper.appsToEndpointString(taskTracker, Seq(app), "\t"))
      }
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
                 @QueryParam("force")@DefaultValue("false") force: Boolean = false,
                 @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthorized(req, resp, KillTask, appId.toRootPath) { implicit principal =>
      val pathId = appId.toRootPath
      def findToKill(appTasks: Iterable[MarathonTask]): Iterable[MarathonTask] = {
        Option(host).fold(appTasks) { hostname =>
          appTasks.filter(_.getHost == hostname || hostname == "*")
        }
      }

      if (scale) {
        val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
        deploymentResult(result(deploymentF))
      }
      else {
        reqToResponse(taskKiller.kill(pathId, findToKill)) {
          tasks => ok(jsonObjString("tasks" -> tasks))
        }
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
                @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    val pathId = appId.toRootPath
    doIfAuthorized(req, resp, KillTask, appId.toRootPath) { implicit principal =>
      def findToKill(appTasks: Iterable[MarathonTask]): Iterable[MarathonTask] = appTasks.find(_.getId == id)

      if (scale) {
        val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
        deploymentResult(result(deploymentF))
      }
      else {
        reqToResponse(taskKiller.kill(pathId, findToKill)) {
          tasks => tasks.headOption.fold(unknownTask(id))(task => ok(jsonObjString("task" -> task)))
        }
      }
    }
  }

  private def reqToResponse(
    future: Future[Iterable[MarathonTask]])(toResponse: Iterable[MarathonTask] => Response): Response = {

    import scala.concurrent.ExecutionContext.Implicits.global
    val response = future.map {
      toResponse
    } recover {
      case UnknownAppException(unknownAppId) => unknownApp(unknownAppId)
    }
    result(response)
  }

}
