package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.codahale.metrics.annotation.Timed
import org.apache.log4j.Logger

import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.api.{ EndpointsHelper, RestResource }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ GroupManager, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject() (service: MarathonSchedulerService,
                                  taskTracker: TaskTracker,
                                  healthCheckManager: HealthCheckManager,
                                  val config: MarathonConf,
                                  groupManager: GroupManager) extends RestResource {

  val log = Logger.getLogger(getClass.getName)
  val GroupTasks = """^((?:.+/)|)\*$""".r

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson(@PathParam("appId") appId: String): Response = {

    def tasks(appIds: Set[PathId]): Set[EnrichedTask] = for {
      id <- appIds
      task <- taskTracker.get(id)
    } yield EnrichedTask(id, task, result(healthCheckManager.status(id, task.getId)))

    val matchingApps = appId match {
      case GroupTasks(gid) =>
        result(groupManager.group(gid.toRootPath))
          .map(_.transitiveApps.map(_.id))
          .getOrElse(Set.empty)
      case _ => Set(appId.toRootPath)
    }

    val running = matchingApps.filter(taskTracker.contains)

    if (running.isEmpty) unknownApp(appId.toRootPath) else ok(Map("tasks" -> tasks(running)))
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt(@PathParam("appId") appId: String): Response = {
    val id = appId.toRootPath
    service.getApp(id).fold(unknownApp(id)) { app =>
      ok(EndpointsHelper.appsToEndpointString(taskTracker, Seq(app), "\t"))
    }
  }

  @DELETE
  @Timed
  def deleteMany(@PathParam("appId") appId: String,
                 @QueryParam("host") host: String,
                 @QueryParam("scale") scale: Boolean = false): Response = {
    val id = appId.toRootPath
    if (taskTracker.contains(id)) {
      val tasks = taskTracker.get(id)

      val toKill = Option(host).fold(tasks) { hostname =>
        tasks.filter(_.getHost == hostname || hostname == "*")
      }

      service.killTasks(id, toKill, scale)
      ok(Map("tasks" -> toKill))
    }
    else {
      unknownApp(id)
    }
  }

  @DELETE
  @Path("{taskId}")
  @Timed
  def deleteOne(@PathParam("appId") appId: String,
                @PathParam("taskId") id: String,
                @QueryParam("scale") scale: Boolean = false): Response = {
    val pathId = appId.toRootPath
    if (taskTracker.contains(pathId)) {
      val tasks = taskTracker.get(pathId)
      tasks.find(_.getId == id).fold(unknownTask(id)) { task =>
        service.killTasks(pathId, Seq(task), scale)
        ok(Map("task" -> task))
      }
    }
    else {
      unknownApp(pathId)
    }
  }
}
