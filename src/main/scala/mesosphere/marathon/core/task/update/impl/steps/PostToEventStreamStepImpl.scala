package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Named

import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Terminated
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.event.{ EventModule, MesosStatusUpdateEvent }
import mesosphere.marathon.state.Timestamp
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
import scala.collection.immutable.Seq

import scala.concurrent.Future

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (
    @Named(EventModule.busName) eventBus: EventStream) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {

    status.getState match {
      case Terminated(_) =>
        postEvent(timestamp, status, task)
      case TASK_RUNNING if task.launched.exists(!_.hasStartedRunning) => // staged, not running
        postEvent(timestamp, status, task)

      case state: TaskState =>
        val taskId = task.taskId
        log.debug(s"Do not post event $state for $taskId of app [${taskId.appId}].")
    }

    Future.successful(())
  }

  private[this] def postEvent(timestamp: Timestamp, status: TaskStatus, task: Task): Unit = {
    val taskId = task.taskId
    task.launched.foreach { launched =>
      log.info(
        "Sending event notification for {} of app [{}]: {}",
        Array[Object](taskId, taskId.appId, status.getState): _*
      )
      eventBus.publish(
        MesosStatusUpdateEvent(
          slaveId = status.getSlaveId.getValue,
          taskId = Task.Id(status.getTaskId),
          taskStatus = status.getState.name,
          message = if (status.hasMessage) status.getMessage else "",
          appId = taskId.appId,
          host = task.agentInfo.host,
          ipAddresses = launched.networking match {
            case networkInfoList: Task.NetworkInfoList => networkInfoList.addresses.to[Seq]
            case _                                     => Seq.empty
          },
          ports = launched.networking match {
            case Task.HostPorts(ports) => ports
            case _                     => Iterable.empty
          },
          version = launched.appVersion.toString,
          timestamp = timestamp.toString
        )
      )

    }
  }

}
