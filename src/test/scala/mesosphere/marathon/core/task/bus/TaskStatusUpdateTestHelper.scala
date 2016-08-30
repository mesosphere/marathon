package mesosphere.marathon.core.task.bus

import java.util.concurrent.TimeUnit

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.instance.{Instance, InstanceStatus$}
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.{Task, TaskStateChange, TaskStateOp}
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{TaskState, TaskStatus}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

class TaskStatusUpdateTestHelper(val wrapped: TaskChanged) {
  def simpleName = wrapped.stateOp match {
    case TaskStateOp.MesosUpdate(_, marathonTaskStatus, mesosStatus, _) =>
      mesosStatus.getState.toString
    case _ => wrapped.stateOp.getClass.getSimpleName
  }
  def status = wrapped.stateOp match {
    case TaskStateOp.MesosUpdate(_, marathonTaskStatus, mesosStatus, _) => mesosStatus
    case _ => throw new scala.RuntimeException("the wrapped stateOp os no MesosUpdate!")
  }
  def reason: String = if (status.hasReason) status.getReason.toString else "no reason"

}

object TaskStatusUpdateTestHelper {
  val log = LoggerFactory.getLogger(getClass)
  def apply(taskChanged: TaskChanged): TaskStatusUpdateTestHelper =
    new TaskStatusUpdateTestHelper(taskChanged)

  private def newTaskID(appId: String) = {
    Instance.Id.forRunSpec(PathId(appId))
  }

  val taskId = newTaskID("/app")
  lazy val defaultTask = MarathonTestHelper.stagedTask(taskId.idString)
  lazy val defaultTimestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0))

  def taskLaunchFor(task: Task, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.LaunchEphemeral(task)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskUpdateFor(task: Task, taskStatus: InstanceStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.MesosUpdate(task, taskStatus, mesosStatus, timestamp)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskExpungeFor(task: Task, taskStatus: InstanceStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    TaskStatusUpdateTestHelper(
      TaskChanged(
        TaskStateOp.MesosUpdate(task, taskStatus, mesosStatus, timestamp),
        TaskStateChange.Expunge(task)))
  }

  def makeMesosTaskStatus(taskId: Instance.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None, timestamp: Timestamp = Timestamp.zero) = {
    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(state)
      .setTimestamp(TimeUnit.MILLISECONDS.convert(timestamp.toDateTime.getMillis, TimeUnit.MICROSECONDS).toDouble)
    maybeHealth.foreach(mesosStatus.setHealthy)
    maybeReason.foreach(mesosStatus.setReason)
    mesosStatus.build()
  }
  def makeTaskStatus(taskId: Instance.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None) = {
    makeMesosTaskStatus(taskId, state, maybeHealth, maybeReason)
  }

  def running(task: Task = defaultTask) = taskUpdateFor(task, InstanceStatus.Running, makeTaskStatus(task.id, TaskState.TASK_RUNNING))

  def runningHealthy(task: Task = defaultTask) = taskUpdateFor(task, InstanceStatus.Running, makeTaskStatus(task.id, TaskState.TASK_RUNNING, maybeHealth = Some(true)))

  def runningUnhealthy(task: Task = defaultTask) = taskUpdateFor(task, InstanceStatus.Running, makeTaskStatus(task.id, TaskState.TASK_RUNNING, maybeHealth = Some(false)))

  def staging(task: Task = defaultTask) = taskUpdateFor(task, InstanceStatus.Staging, makeTaskStatus(task.id, TaskState.TASK_STAGING))

  def finished(task: Task = defaultTask) = taskExpungeFor(task, InstanceStatus.Finished, makeTaskStatus(task.id, TaskState.TASK_FINISHED))

  def lost(reason: Reason, task: Task = defaultTask) = {
    val mesosStatus = makeTaskStatus(task.id, TaskState.TASK_LOST, maybeReason = Some(reason))
    val marathonTaskStatus = InstanceStatus(mesosStatus)

    marathonTaskStatus match {
      case _: InstanceStatus.Terminal =>
        taskExpungeFor(task, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(task, marathonTaskStatus, mesosStatus)
    }
  }

  def killed(task: Task = defaultTask) = taskExpungeFor(task, InstanceStatus.Killed, makeTaskStatus(task.id, TaskState.TASK_KILLED))

  def killing(task: Task = defaultTask) = taskUpdateFor(task, InstanceStatus.Killing, makeTaskStatus(task.id, TaskState.TASK_KILLING))

  def error(task: Task = defaultTask) = taskExpungeFor(task, InstanceStatus.Error, makeTaskStatus(task.id, TaskState.TASK_ERROR))

  def failed(task: Task = defaultTask) = taskExpungeFor(task, InstanceStatus.Failed, makeTaskStatus(task.id, TaskState.TASK_FAILED))
}
