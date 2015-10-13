package mesosphere.marathon.core.task.tracker.impl.steps

import javax.inject.Named

import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.event.{ EventModule, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskState.{
  TASK_ERROR,
  TASK_FAILED,
  TASK_FINISHED,
  TASK_KILLED,
  TASK_LOST,
  TASK_RUNNING
}
import org.apache.mesos.Protos.TaskStatus
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
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        // Remove from our internal list
        maybeTask match {
          case Some(task) => postEvent(appId, status, task)
          case None       => log.warn(s"Task not found. Do not post event for '{}'", taskId.getValue)
        }

      case TASK_RUNNING if !maybeTask.exists(_.hasStartedAt) => // staged, not running
        maybeTask.foreach { task => postEvent(appId, status, task) }

      case _ =>
      // ignore
    }

    Future.successful(())
  }

  private[this] def postEvent(appId: PathId, status: TaskStatus, task: MarathonTask): Unit = {
    log.info("Sending event notification for task [{}]: {}", Array[Object](task.getId, status.getState): _*)
    import scala.collection.JavaConverters._
    eventBus.publish(
      MesosStatusUpdateEvent(
        status.getSlaveId.getValue,
        status.getTaskId.getValue,
        status.getState.name,
        if (status.hasMessage) status.getMessage else "",
        appId,
        task.getHost,
        task.getPortsList.asScala,
        task.getVersion
      )
    )
  }

}
