package mesosphere.marathon.core.task.update.impl.steps

import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.task.{ EffectiveTaskStateChange, Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.core.event.MesosStatusUpdateEvent
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.collection.immutable.Seq

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (eventBus: EventStream, clock: Clock) extends TaskUpdateStep {

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    import TaskStateOp.MesosUpdate
    val taskState = inferTaskState(taskChanged)

    taskChanged match {
      // the task was updated or expunged due to a MesosStatusUpdate
      // In this case, we're interested in the mesosStatus
      case TaskChanged(MesosUpdate(oldTask, taskStatus, mesosStatus, now), EffectiveTaskStateChange(task)) =>
        postEvent(clock.now(), taskState, Some(mesosStatus), task, inferVersion(task, Some(oldTask)))

      case TaskChanged(_, TaskStateChange.Update(newState, oldState)) =>
        postEvent(clock.now(), taskState, newState.mesosStatus, newState, inferVersion(newState, oldState))

      case TaskChanged(_, TaskStateChange.Expunge(task)) =>
        postEvent(clock.now(), taskState, task.mesosStatus, task, inferVersion(task, None))

      case _ =>
        log.debug("Ignoring noop for {}", taskChanged.taskId)
    }

    Future.successful(())
  }

  // inconvenient for now because not all tasks have a version
  private[this] def inferVersion(newTask: Task, oldTask: Option[Task]): Timestamp = {
    newTask.version.getOrElse(oldTask.fold(Timestamp(0))(_.version.getOrElse(Timestamp(0))))
  }

  private[this] def inferTaskState(taskChanged: TaskChanged): MarathonTaskStatus = {
    (taskChanged.stateOp, taskChanged.stateChange) match {
      case (TaskStateOp.MesosUpdate(_, status, mesosStatus, _), _) => status
      case (_, TaskStateChange.Update(newState, maybeOldState)) => newState.status.taskStatus

      // TODO: the task status is not updated in this case, so we "assume" KILLED here
      case (_, TaskStateChange.Expunge(task)) => MarathonTaskStatus.Killed

      case _ => throw new IllegalStateException(s"received unexpected $taskChanged")
    }
  }

  object Terminal {
    def unapply(status: MarathonTaskStatus): Option[MarathonTaskStatus] = status match {
      case _: MarathonTaskStatus.Terminal => Some(status)
      case _: Any => None
    }
  }

  private[this] def postEvent(
    timestamp: Timestamp,
    taskStatus: MarathonTaskStatus,
    maybeStatus: Option[TaskStatus],
    task: Task,
    version: Timestamp): Unit = {

    val taskId = task.taskId
    val slaveId = maybeStatus.fold("n/a")(_.getSlaveId.getValue)
    val message = maybeStatus.fold("")(status => if (status.hasMessage) status.getMessage else "")
    val host = task.agentInfo.host
    val ipAddresses = maybeStatus.flatMap(status => Task.MesosStatus.ipAddresses(status))
    val ports = task.launched.fold(Seq.empty[Int])(_.hostPorts)

    log.info("Sending event notification for {} of app [{}]: {}", taskId, taskId.runSpecId, taskStatus.toMesosStateName)
    eventBus.publish(
      MesosStatusUpdateEvent(
        slaveId,
        taskId,
        // TODO if we posted the MarathonTaskStatus.toString, consumers would not get "TASK_STAGING", but "Staging"
        taskStatus.toMesosStateName,
        message,
        appId = taskId.runSpecId,
        host,
        ipAddresses,
        ports = ports,
        version = version.toString,
        timestamp = timestamp.toString
      )
    )
  }

}
