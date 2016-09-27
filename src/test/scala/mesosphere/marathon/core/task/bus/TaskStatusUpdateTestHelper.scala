package mesosphere.marathon.core.task.bus

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.instance.update._
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus, TestInstanceBuilder }
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

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
}

object TaskStatusUpdateTestHelper {
  val log = LoggerFactory.getLogger(getClass)
  def apply(operation: InstanceUpdateOperation, effect: InstanceUpdateEffect): TaskStatusUpdateTestHelper =
    new TaskStatusUpdateTestHelper(operation, effect)

  lazy val defaultInstance = TestInstanceBuilder.newBuilder(PathId("/app")).addTaskStaged().getInstance()
  lazy val defaultTimestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0))

  def taskLaunchFor(instance: Instance) = {
    val operation = InstanceUpdateOperation.LaunchEphemeral(instance)
    val effect = InstanceUpdateEffect.Update(operation.instance, oldState = None, events = Nil)
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def taskUpdateFor(instance: Instance, taskStatus: InstanceStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val operation = InstanceUpdateOperation.MesosUpdate(instance, taskStatus, mesosStatus, timestamp)
    val effect = operation.instance.update(operation)
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def taskExpungeFor(instance: Instance, taskStatus: InstanceStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val operation = InstanceUpdateOperation.MesosUpdate(instance, taskStatus, mesosStatus, timestamp)
    val effect = operation.instance.update(operation)
    if (!effect.isInstanceOf[InstanceUpdateEffect.Expunge]) {
      throw new RuntimeException(s"Applying a MesosUpdate with status $taskStatus did not result in an Expunge effect but in a $effect")
    }
    TaskStatusUpdateTestHelper(operation, effect)
  }

  def makeMesosTaskStatus(taskId: Task.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None, maybeMessage: Option[String] = None, timestamp: Timestamp = Timestamp.zero) = {
    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(state)
      .setTimestamp(TimeUnit.MILLISECONDS.convert(timestamp.toDateTime.getMillis, TimeUnit.MICROSECONDS).toDouble)
    maybeHealth.foreach(mesosStatus.setHealthy)
    maybeReason.foreach(mesosStatus.setReason)
    maybeMessage.foreach(mesosStatus.setMessage)
    mesosStatus.build()
  }
  def makeTaskStatus(taskId: Task.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None, maybeMessage: Option[String] = None) = {
    makeMesosTaskStatus(taskId, state, maybeHealth, maybeReason, maybeMessage)
  }

  def running(instance: Instance = defaultInstance) = taskUpdateFor(instance, InstanceStatus.Running, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_RUNNING))

  def runningHealthy(instance: Instance = defaultInstance) = taskUpdateFor(instance, InstanceStatus.Running, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_RUNNING, maybeHealth = Some(true)))

  def runningUnhealthy(instance: Instance = defaultInstance) = taskUpdateFor(instance, InstanceStatus.Running, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_RUNNING, maybeHealth = Some(false)))

  def staging(instance: Instance = defaultInstance) = taskUpdateFor(instance, InstanceStatus.Staging, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_STAGING))

  def finished(instance: Instance = defaultInstance) = taskExpungeFor(instance, InstanceStatus.Finished, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_FINISHED))

  def lost(reason: Reason, instance: Instance = defaultInstance, maybeMessage: Option[String] = None) = {
    val mesosStatus = makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_LOST, maybeReason = Some(reason), maybeMessage = maybeMessage)
    val marathonTaskStatus = MarathonTaskStatus(mesosStatus)

    marathonTaskStatus match {
      case _: InstanceStatus.Terminal =>
        taskExpungeFor(instance, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(instance, marathonTaskStatus, mesosStatus)
    }
  }

  def unreachable(instance: Instance = defaultInstance) = {
    val mesosStatus = makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_UNREACHABLE)
    val marathonTaskStatus = MarathonTaskStatus(mesosStatus)

    marathonTaskStatus match {
      case _: InstanceStatus.Terminal =>
        taskExpungeFor(instance, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(instance, marathonTaskStatus, mesosStatus)
    }
  }

  def killed(instance: Instance = defaultInstance) = {
    // TODO(PODS): the method signature should allow passing a taskId
    val taskId = instance.tasks.head.taskId
    taskExpungeFor(instance, InstanceStatus.Killed, makeTaskStatus(taskId, TaskState.TASK_KILLED))
  }

  def killing(instance: Instance = defaultInstance) = taskUpdateFor(instance, InstanceStatus.Killing, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_KILLING))

  def error(instance: Instance = defaultInstance) = taskExpungeFor(instance, InstanceStatus.Error, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_ERROR))

  def failed(instance: Instance = defaultInstance) = taskExpungeFor(instance, InstanceStatus.Failed, makeTaskStatus(Task.Id.forInstanceId(instance.instanceId, None), TaskState.TASK_FAILED))
}
