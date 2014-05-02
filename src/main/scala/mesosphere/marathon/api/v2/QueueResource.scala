package mesosphere.marathon.api.v2

import javax.ws.rs.{Path, GET, Consumes, Produces}
import javax.ws.rs.core.MediaType
import com.codahale.metrics.annotation.Timed
import javax.inject.Inject
import mesosphere.marathon.tasks.TaskQueue

/**
 * @author Tobi Knaup
 */

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject()(taskQueue: TaskQueue) {

  @GET
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index() = {
    Map("queue" -> taskQueue.queue)
  }
}
