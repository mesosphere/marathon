package mesosphere.marathon.api.v2

import javax.ws.rs._
import com.codahale.metrics.annotation.Timed
import javax.ws.rs.core.{MediaType, Response}
import scala.{Array, Some}
import scala.concurrent.Await
import org.apache.mesos.Protos.TaskID
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v1.Implicits
import java.util.logging.Logger

/**
 * @author Tobi Knaup
 */

@Produces(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject()(service: MarathonSchedulerService,
                                 taskTracker: TaskTracker) {

  import Implicits._

  val log = Logger.getLogger(getClass.getName)


  @GET
  @Timed
  def show(@PathParam("appId") appId: String) = {
    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val result = Map(appId -> tasks.map(s => s: Map[String, Object]))
      Response.ok(result).build
    } else {
      Response.noContent.status(404).build
    }
  }

  @DELETE
  @Path("{taskId}")
  @Timed
  def delete(@PathParam("appId") appId: String,
             @QueryParam("host") host: String,
             @PathParam("taskId") id: String = "*",
             @QueryParam("scale") scale: Boolean = false) = {
    val tasks = taskTracker.get(appId)
    val toKill = tasks.filter(x =>
      x.getHost == host || x.getId == id || host == "*"
    )

    if (scale) {
      service.getApp(appId) match {
        case Some(appDef) =>
          appDef.instances = appDef.instances - toKill.size

          Await.result(service.scaleApp(appDef, false), service.defaultWait)
        case None =>
      }
    }

    toKill.map({task =>
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")
      service.driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
      task: Map[String, Object]
    })
  }
}