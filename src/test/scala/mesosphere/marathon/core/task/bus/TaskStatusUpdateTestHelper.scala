package mesosphere.marathon
package core.task.bus

import java.time.{OffsetDateTime, ZoneOffset}

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update._
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{TaskState, TaskStatus}

class TaskStatusUpdateTestHelper(val operation: InstanceUpdateOperation, val effect: InstanceUpdateEffect) {
  def simpleName = operation match {
    case InstanceUpdateOperation.MesosUpdate(_, marathonTaskStatus, mesosStatus, _) =>
      mesosStatus.getState.toString
    case _ => operation.getClass.getSimpleName
  }
  def status = operation match {
    case InstanceUpdateOperation.MesosUpdate(_, marathonTaskStatus, mesosStatus, _) => mesosStatus
    case _ => throw new scala.RuntimeException("the wrapped stateOp os no MesosUpdate!")
  }
  def reason: String = if (status.hasReason) status.getReason.toString else "no reason"
  def wrapped: InstanceChange = effect match {
    case InstanceUpdateEffect.Update(instance, old, events) => InstanceUpdated(instance, old.map(_.state), events)
    case InstanceUpdateEffect.Expunge(instance, events) => InstanceDeleted(instance, None, events)
    case _ => throw new scala.RuntimeException(s"The wrapped effect does not result in an update or expunge: $effect")
  }
  private[this] def instanceFromOperation: Instance = operation match {
    case launch: InstanceUpdateOperation.LaunchEphemeral => launch.instance
    case update: InstanceUpdateOperation.MesosUpdate => update.instance
    case _ => throw new RuntimeException(s"Unable to fetch instance from ${operation.getClass.getSimpleName}")
  }
  def updatedInstance: Instance = effect match {
    case InstanceUpdateEffect.Update(instance, old, events) => instance
    case InstanceUpdateEffect.Expunge(instance, events) => instance
    case _ => instanceFromOperation
  }

}

object TaskStatusUpdateTestHelper {
  def apply(operation: InstanceUpdateOperation, effect: InstanceUpdateEffect): TaskStatusUpdateTestHelper =
    new TaskStatusUpdateTestHelper(operation, effect)

  lazy val defaultInstance = TestInstanceBuilder.newBuilder(PathId("/app")).addTaskStaged().getInstance()
  lazy val defaultTimestamp = Timestamp(OffsetDateTime.of(2015, 2, 3, 12, 30, 0, 0, ZoneOffset.UTC))

  def taskLaunchFor(instance: Instance, timestamp: Timestamp = defaultTimestamp) = {
    val operation = InstanceUpdateOperation.LaunchEphemeral(instance)
    val events = InstanceChangedEventsGenerator.events(instance, task = None, timestamp, previousCondition = None)
    val effect = InstanceUpdateEffect.Update(operation.instance, oldState = None, events = events)
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def taskUpdateFor(instance: Instance, taskCondition: Condition, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val operation = InstanceUpdateOperation.MesosUpdate(instance, taskCondition, mesosStatus, timestamp)
    val effect = InstanceUpdater.mesosUpdate(instance, operation)
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def taskExpungeFor(instance: Instance, taskCondition: Condition, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val operation = InstanceUpdateOperation.MesosUpdate(instance, taskCondition, mesosStatus, timestamp)
    val effect = InstanceUpdater.mesosUpdate(instance, operation)
    if (!effect.isInstanceOf[InstanceUpdateEffect.Expunge]) {
      throw new RuntimeException(s"Applying a MesosUpdate with status $taskCondition did not result in an Expunge effect but in a $effect")
    }
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def taskId(instance: Instance, container: Option[MesosContainer]): Task.Id = {
    val taskId = instance.tasksMap.headOption.map(_._1)
    taskId.getOrElse(Task.Id.forInstanceId(instance.instanceId, container))
  }

  def running(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.running(taskId)
    taskUpdateFor(instance, Condition.Running, status)
  }

  def runningHealthy(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.runningHealthy(taskId)
    taskUpdateFor(instance, Condition.Running, status)
  }

  def runningUnhealthy(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.runningUnhealthy(taskId)
    taskUpdateFor(instance, Condition.Running, status)
  }

  def staging(instance: Instance = defaultInstance) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId)
    val status = MesosTaskStatusTestHelper.staging(taskId)
    taskUpdateFor(instance, Condition.Staging, status)
  }

  def finished(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.finished(taskId)
    taskUpdateFor(instance, Condition.Finished, status)
  }

  def lost(reason: Reason, instance: Instance = defaultInstance, maybeMessage: Option[String] = None, timestamp: Timestamp = defaultTimestamp) = {
    val taskId = instance.appTask.taskId
    val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(
      state = TaskState.TASK_LOST,
      maybeReason = Some(reason), maybeMessage = maybeMessage,
      taskId = taskId,
      timestamp = timestamp
    )
    val marathonTaskStatus = TaskCondition(mesosStatus)

    marathonTaskStatus match {
      case _: Condition.Terminal =>
        taskExpungeFor(instance, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(instance, marathonTaskStatus, mesosStatus, timestamp)
    }
  }

  def unreachable(instance: Instance = defaultInstance) = {
    val mesosStatus = MesosTaskStatusTestHelper.unreachable(Task.Id.forInstanceId(instance.instanceId))
    val marathonTaskStatus = TaskCondition(mesosStatus)

    marathonTaskStatus match {
      case _: Condition.Terminal =>
        taskExpungeFor(instance, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(instance, marathonTaskStatus, mesosStatus)
    }
  }

  def killed(instance: Instance = defaultInstance) = {
    // TODO(PODS): the method signature should allow passing a taskId
    val (taskId, _) = instance.tasksMap.head
    val status = MesosTaskStatusTestHelper.killed(taskId)
    taskExpungeFor(instance, Condition.Killed, status)
  }

  def killing(instance: Instance = defaultInstance) = {
    val status = MesosTaskStatusTestHelper.killing(Task.Id.forInstanceId(instance.instanceId))
    taskUpdateFor(instance, Condition.Killing, status)
  }

  def error(instance: Instance = defaultInstance) = {
    val status = MesosTaskStatusTestHelper.error(Task.Id.forInstanceId(instance.instanceId))
    taskExpungeFor(instance, Condition.Error, status)
  }
  def failed(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.failed(taskId)
    taskUpdateFor(instance, Condition.Failed, status)
  }

  def gone(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.gone(taskId)
    taskUpdateFor(instance, Condition.Gone, status)
  }

  def dropped(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.dropped(taskId)
    taskUpdateFor(instance, Condition.Dropped, status)
  }

  def unknown(instance: Instance = defaultInstance, container: Option[MesosContainer] = None) = {
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val status = MesosTaskStatusTestHelper.unknown(taskId)
    taskUpdateFor(instance, Condition.Unknown, status)
  }
}
