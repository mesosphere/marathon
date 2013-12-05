package mesosphere.marathon.api.v2

import javax.ws.rs._
import scala.Array
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v1.Implicits
import java.util.logging.Logger
import com.codahale.metrics.annotation.Timed

/**
 * @author Tobi Knaup
 */

@Path("v2/tasks")
@Produces(Array(MediaType.APPLICATION_JSON))
class TasksResource @Inject()(
                               service: MarathonSchedulerService,
                               taskTracker: TaskTracker) {

  import Implicits._

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index() = {
    taskTracker.list.map { case ((key, setOfTasks)) =>
      // TODO teach Jackson how to serialize a MarathonTask instead
      // TODO JSON format is weird
      (key, setOfTasks.tasks.map(s => s: Map[String, Object]))
    }
  }
}
