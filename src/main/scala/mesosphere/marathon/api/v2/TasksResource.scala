package mesosphere.marathon.api.v2

import javax.ws.rs._
import scala.Array
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v1.Implicits
import mesosphere.marathon.api.EndpointsHelper
import java.util.logging.Logger
import com.codahale.metrics.annotation.Timed

/**
 * @author Tobi Knaup
 */

@Path("v2/tasks")
class TasksResource @Inject()(service: MarathonSchedulerService,
                              taskTracker: TaskTracker) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Timed
  def indexJson() = {
    taskTracker.list.map { case ((key, setOfTasks)) =>
      // TODO teach Jackson how to serialize a MarathonTask instead
      // TODO JSON format is weird
      (key, setOfTasks.tasks)
    }
  }

  @GET
  @Produces(Array(MediaType.TEXT_PLAIN))
  @Timed
  def indexTxt() = EndpointsHelper.appsToEndpointString(
    taskTracker,
    service.listApps(),
    "\t"
  )

}
