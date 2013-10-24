package mesosphere.marathon.api.v1

import javax.ws.rs._
import mesosphere.marathon.MarathonSchedulerService
import javax.ws.rs.core.MediaType
import javax.inject.Inject
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.tasks.TaskTracker
import java.util.logging.Logger
import org.apache.mesos.Protos.TaskID

@Path("v1/tasks")
@Produces(Array(MediaType.APPLICATION_JSON))
class TasksResource @Inject()(
    service: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  import Implicits._

  val log = Logger.getLogger(getClass.getName)


  @GET
  @Timed
  def list = {
    taskTracker.list().map { case ((key, setOfTasks)) =>
      // TODO teach Jackson how to serialize a MarathonTask instead
      (key, setOfTasks.map(s => s: Map[String, Object]))
    }
  }

  @POST
  @Path("kill")
  @Timed
  def killTasks(@QueryParam("appId") appId: String,
                @QueryParam("host") host: String) = {
    taskTracker.get(appId)
      .filter(_.getHost == host || host == "*")
      .map(task => {
        log.info(f"Killing task ${task.getId} on host ${task.getHost}")
        service.driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
        task: Map[String, Object]
      })
  }
}
