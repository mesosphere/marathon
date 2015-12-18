package mesosphere.marathon.core.task.tracker.impl.steps

import javax.inject.Named

import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.event.{ EventModule, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.MarathonTasks
import org.apache.mesos.Protos.TaskState.{
  TASK_ERROR,
  TASK_FAILED,
  TASK_FINISHED,
  TASK_KILLED,
  TASK_LOST,
  TASK_RUNNING
}
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (
    @Named(EventModule.busName) eventBus: EventStream) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        postEvent(timestamp, appId, status, task)
      case TASK_RUNNING if !task.hasStartedAt => // staged, not running
        postEvent(timestamp, appId, status, task)

      case state: TaskState =>
        val taskId = status.getTaskId.getValue
        log.debug(s"Do not post event $state for [$taskId] of app [$appId].")
    }

    Future.successful(())
  }

  private[this] def postEvent(timestamp: Timestamp, appId: PathId, status: TaskStatus, task: MarathonTask): Unit = {
    log.info(
      "Sending event notification for task [{}] of app [{}]: {}",
      Array[Object](task.getId, appId, status.getState): _*
    )
    import scala.collection.JavaConverters._
    eventBus.publish(
      MesosStatusUpdateEvent(
        status.getSlaveId.getValue,
        status.getTaskId.getValue,
        status.getState.name,
        if (status.hasMessage) status.getMessage else "",
        appId,
        task.getHost,
        MarathonTasks.ipAddresses(task),
        task.getPortsList.asScala,
        task.getVersion,
        timestamp = timestamp.toString
      )
    )
  }

}
