package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{ MediaType, Response }
import mesosphere.marathon.state.{PathId, GroupManager}
import mesosphere.marathon.state.PathId._
import javax.inject.Inject
import mesosphere.marathon.{MarathonConf, MarathonSchedulerService}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.{ Responses, EndpointsHelper }
import org.apache.log4j.Logger
import mesosphere.marathon.health.HealthCheckManager
import scala.concurrent.{Awaitable, Await}
import mesosphere.marathon.api.v2.json.EnrichedTask

/**
  * @author Tobi Knaup
  */

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject() (service: MarathonSchedulerService,
                                  taskTracker: TaskTracker,
                                  healthCheckManager: HealthCheckManager,
                                  config: MarathonConf,
                                  groupManager: GroupManager
                                   ) {

  val log = Logger.getLogger(getClass.getName)
  val GroupTasks = """^((?:.+/)|)\*$""".r

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson(@PathParam("appId") appId: String) = {

    def tasks(appIds: Set[PathId]) = for {
      id <- appIds
      task <- taskTracker.get(id)
    } yield EnrichedTask(id, task, result(healthCheckManager.status(id, task.getId)))

    val matchingApps = appId match {
      case GroupTasks(gid) => result(groupManager.group(gid.toRootPath)).map(_.transitiveApps.map(_.id)).getOrElse(Set.empty)
      case _ => Set(appId.toRootPath)
    }

    val running = matchingApps.filter(taskTracker.contains)

    if (running.isEmpty) Responses.unknownApp(appId.toRootPath) else  Response.ok(Map("tasks" -> tasks(running))).build
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String) = {
    val id = appId.toRootPath
    service.getApp(id).fold(Responses.unknownApp(id)) { app =>
      Response.ok(
        EndpointsHelper.appsToEndpointString(
          taskTracker,
          Seq(app),
          "\t"
        )
      ).build
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale") scale: Boolean = false) = {
    val id = appId.toRootPath
    if (taskTracker.contains(id)) {
      val tasks = taskTracker.get(id)

      val toKill = Option(host).fold(tasks) { hostname =>
        tasks.filter(_.getHost == hostname || hostname == "*")
      }

      service.killTasks(id, toKill, scale)
      Response.ok(Map("tasks" -> toKill)).build
    }
    else {
      Responses.unknownApp(id)
    }
  }

  @DELETE
  @Path("{taskId}")
  @Timed
  def deleteOne(@PathParam("appId") appId: String,
                @PathParam("taskId") id: String,
                @QueryParam("scale") scale: Boolean = false) = {
    val pathId = appId.toRootPath
    if (taskTracker.contains(pathId)) {
      val tasks = taskTracker.get(pathId)
      tasks.find(_.getId == id).fold(Responses.unknownTask(id)) { task =>
        service.killTasks(pathId, Seq(task), scale)
        Response.ok(Map("task" -> task)).build
      }
    }
    else {
      Responses.unknownApp(pathId)
    }
  }

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
}
