package mesosphere.marathon.core.task.bus

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
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
    Task.Id.forRunSpec(PathId(appId))
  }

  val taskId = newTaskID("/app")
  lazy val defaultTask = MarathonTestHelper.stagedTask(taskId.idString)
  lazy val defaultTimestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0))

  def taskLaunchFor(task: Task, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.LaunchEphemeral(task)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskUpdateFor(task: Task, taskStatus: MarathonTaskStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.MesosUpdate(task, taskStatus, mesosStatus, timestamp)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskExpungeFor(task: Task, taskStatus: MarathonTaskStatus, mesosStatus: TaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    TaskStatusUpdateTestHelper(
      TaskChanged(
        TaskStateOp.MesosUpdate(task, taskStatus, mesosStatus, timestamp),
        TaskStateChange.Expunge(task)))
  }

  def makeMesosTaskStatus(taskId: Task.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None, maybeMessage: Option[String] = None, timestamp: Timestamp = Timestamp.zero) = {
    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(state)
    maybeHealth.foreach(mesosStatus.setHealthy)
    maybeReason.foreach(mesosStatus.setReason)
    maybeMessage.foreach(mesosStatus.setMessage)
    mesosStatus.build()
  }
  def makeTaskStatus(taskId: Task.Id, state: TaskState, maybeHealth: Option[Boolean] = None, maybeReason: Option[TaskStatus.Reason] = None, maybeMessage: Option[String] = None) = {
    makeMesosTaskStatus(taskId, state, maybeHealth, maybeReason, maybeMessage)
  }

  def running(task: Task = defaultTask) = taskUpdateFor(task, MarathonTaskStatus.Running, makeTaskStatus(task.taskId, TaskState.TASK_RUNNING))

  def runningHealthy(task: Task = defaultTask) = taskUpdateFor(task, MarathonTaskStatus.Running, makeTaskStatus(task.taskId, TaskState.TASK_RUNNING, maybeHealth = Some(true)))

  def runningUnhealthy(task: Task = defaultTask) = taskUpdateFor(task, MarathonTaskStatus.Running, makeTaskStatus(task.taskId, TaskState.TASK_RUNNING, maybeHealth = Some(false)))

  def staging(task: Task = defaultTask) = taskUpdateFor(task, MarathonTaskStatus.Staging, makeTaskStatus(task.taskId, TaskState.TASK_STAGING))

  def finished(task: Task = defaultTask) = taskExpungeFor(task, MarathonTaskStatus.Finished, makeTaskStatus(task.taskId, TaskState.TASK_FINISHED))

  def lost(reason: Reason, task: Task = defaultTask, maybeMessage: Option[String] = None) = {
    val mesosStatus = makeTaskStatus(task.taskId, TaskState.TASK_LOST, maybeReason = Some(reason), maybeMessage = maybeMessage)
    val marathonTaskStatus = MarathonTaskStatus(mesosStatus)

    marathonTaskStatus match {
      case _: MarathonTaskStatus.Terminal =>
        taskExpungeFor(task, marathonTaskStatus, mesosStatus)

      case _ =>
        taskUpdateFor(task, marathonTaskStatus, mesosStatus)
    }
  }

  def killed(task: Task = defaultTask) = taskExpungeFor(task, MarathonTaskStatus.Killed, makeTaskStatus(task.taskId, TaskState.TASK_KILLED))

  def killing(task: Task = defaultTask) = taskUpdateFor(task, MarathonTaskStatus.Killing, makeTaskStatus(task.taskId, TaskState.TASK_KILLING))

  def error(task: Task = defaultTask) = taskExpungeFor(task, MarathonTaskStatus.Error, makeTaskStatus(task.taskId, TaskState.TASK_ERROR))

  def failed(task: Task = defaultTask) = taskExpungeFor(task, MarathonTaskStatus.Failed, makeTaskStatus(task.taskId, TaskState.TASK_FAILED))
}
