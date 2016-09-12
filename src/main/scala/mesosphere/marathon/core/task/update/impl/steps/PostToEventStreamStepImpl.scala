package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.task.{ EffectiveTaskStateChange, Task, TaskStateChange, InstanceStateOp }
import mesosphere.marathon.core.event.{ InstanceChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.collection.immutable.Seq

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (eventBus: EventStream, clock: Clock)
    extends TaskUpdateStep with InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def process(update: InstanceChange): Future[Done] = {
    log.info("Sending instance change event for {} of runSpec [{}]: {}", update.id, update.runSpecId, update.status)
    eventBus.publish(
      InstanceChanged(update.id, update.runSpecVersion, update.runSpecId, update.status, update.instance))

    // TODO(PODS): if the instance is based on an AppDefinition, publish a MesosStatusUpdateEvent equal to one
    // that would have been published before in order to stay backwards compatible
    //    update.instance match {
    //      case appInstance: AppInstance =>
    //        eventBus.publish(MesosStatusUpdateEvent(...))
    //      case _ =>
    //         // do nothing
    //    }

    Future.successful(Done)
  }

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    import InstanceStateOp.MesosUpdate
    val taskState = inferTaskState(taskChanged)

    taskChanged match {
      // the task was updated or expunged due to a MesosStatusUpdate
      // In this case, we're interested in the mesosStatus
      case TaskChanged(MesosUpdate(instance, taskStatus, mesosStatus, now), EffectiveTaskStateChange(task)) =>
        val task = instance.tasks.find(_.taskId == Task.Id(mesosStatus.getTaskId))
          .getOrElse(throw new RuntimeException("Cannot map TaskStatus to a task in " + instance.instanceId))
        postEvent(clock.now(), taskState, Some(mesosStatus), task, inferVersion(task, Some(task)))

      case TaskChanged(_, TaskStateChange.Update(newState, oldState)) =>
        postEvent(clock.now(), taskState, newState.mesosStatus, newState, inferVersion(newState, oldState))

      case TaskChanged(_, TaskStateChange.Expunge(task)) =>
        postEvent(clock.now(), taskState, task.mesosStatus, task, inferVersion(task, None))

      case _ =>
        log.debug("Ignoring noop for {}", taskChanged.instanceId)
    }

    Future.successful(())
  }

  // inconvenient for now because not all tasks have a version
  private[this] def inferVersion(newTask: Task, oldTask: Option[Task]): Timestamp = {
    newTask.version.getOrElse(oldTask.fold(Timestamp(0))(_.version.getOrElse(Timestamp(0))))
  }

  private[this] def inferTaskState(taskChanged: TaskChanged): InstanceStatus = {
    (taskChanged.stateOp, taskChanged.stateChange) match {
      case (InstanceStateOp.MesosUpdate(_, status, mesosStatus, _), _) => status
      case (_, TaskStateChange.Update(newState, maybeOldState)) => newState.status.taskStatus

      // TODO: the task status is not updated in this case, so we "assume" KILLED here
      case (_, TaskStateChange.Expunge(task)) => InstanceStatus.Killed

      case _ => throw new IllegalStateException(s"received unexpected $taskChanged")
    }
  }

  object Terminal {
    def unapply(status: InstanceStatus): Option[InstanceStatus] = status match {
      case _: InstanceStatus.Terminal => Some(status)
      case _: Any => None
    }
  }

  private[this] def postEvent(
    timestamp: Timestamp,
    taskStatus: InstanceStatus,
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
