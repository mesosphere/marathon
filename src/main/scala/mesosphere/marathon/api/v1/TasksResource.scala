package mesosphere.marathon.api.v1

import javax.ws.rs._
import mesosphere.marathon.MarathonSchedulerService
import javax.ws.rs.core.{Context, Response, MediaType}
import javax.inject.{Named, Inject}
import javax.validation.Valid
import com.codahale.metrics.annotation.Timed
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{EventModule, ApiPostEvent}
import javax.servlet.http.HttpServletRequest
import mesosphere.marathon.tasks.{TaskIDUtil, TaskTracker}
import java.util.logging.Logger
import org.apache.mesos.Protos.TaskID

@Path("v1/tasks")
@Produces(Array(MediaType.APPLICATION_JSON))
class TasksResource @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def list = {
    taskTracker.list()
  }

  @GET
  @Path("killTasks")
  @Timed
  def killTasks(@QueryParam("appId") appId: String,
                @QueryParam("hostname") hostname: String) = {
    val tasks = taskTracker.list().filter(  _._1 == appId || appId == "*"  )
      .filter(_._2 == hostname || hostname == "*").flatMap(_._2)

    tasks.foreach(task => {
      log.warning(f"Killing task: ${task.getId} on host: ${task.getHost}")
      service.driver.killTask(TaskID.newBuilder().setValue(task.getId).build())
    })
  }
}
