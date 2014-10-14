package mesosphere.marathon.api.v2

import javax.ws.rs.{ Path, GET, Consumes, Produces }
import javax.ws.rs.core.{ MediaType, Response }
import com.codahale.metrics.annotation.Timed
import javax.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.tasks.TaskQueue

@Path("v2/queue")
@Consumes(Array(MediaType.APPLICATION_JSON))
class QueueResource @Inject() (
    taskQueue: TaskQueue,
    val config: MarathonConf) extends RestResource {

  @GET
  @Timed
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(): Response = {
    ok(Map("queue" -> taskQueue.list))
  }
}
