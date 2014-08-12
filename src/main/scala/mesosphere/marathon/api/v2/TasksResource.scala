package mesosphere.marathon.api.v2

import javax.ws.rs._
import scala.Array
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import mesosphere.marathon.api.{ EndpointsHelper, RestResource }
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.health.{ Health, HealthCheckManager }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.marathon.tasks.TaskTracker
import org.apache.log4j.Logger
import com.codahale.metrics.annotation.Timed

@Path("v2/tasks")
class TasksResource @Inject() (
    service: MarathonSchedulerService,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    val config: MarathonConf) extends RestResource {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson() = Map(
    "tasks" -> taskTracker.list.flatMap {
      case (appId, setOfTasks) =>
        setOfTasks.tasks.map { task =>
          EnrichedTask(
            appId,
            task,
            result(healthCheckManager.status(appId, task.getId))
          )
        }
    }
  )

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt() = EndpointsHelper.appsToEndpointString(
    taskTracker,
    service.listApps().toSeq,
    "\t"
  )

}
