package mesosphere.marathon.core.task.bus

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.{ TaskStatus, TaskState }
import org.joda.time.DateTime

class TaskStatusUpdateTestHelper(val wrapped: TaskChanged) {
  def simpleName = wrapped.stateOp match {
    case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.WithMesosStatus(mesosStatus), _) =>
      mesosStatus.getState.toString
    case _ => wrapped.stateOp.getClass.getSimpleName
  }
  def status = wrapped.stateOp match {
    case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.WithMesosStatus(mesosStatus), _) => mesosStatus
    case _ => throw new scala.RuntimeException("the wrapped stateOp os no MesosUpdate!")
  }
}

object TaskStatusUpdateTestHelper {
  def apply(taskChanged: TaskChanged): TaskStatusUpdateTestHelper =
    new TaskStatusUpdateTestHelper(taskChanged)

  private def newTaskID(appId: String) = {
    Task.Id.forApp(PathId(appId))
  }

  val taskId = newTaskID("/app")
  lazy val defaultTask = MarathonTestHelper.stagedTask(taskId.idString)
  lazy val defaultTimestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0))

  def taskLaunchFor(task: Task, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.LaunchEphemeral(task)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskUpdateFor(task: Task, taskStatus: MarathonTaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    val taskStateOp = TaskStateOp.MesosUpdate(task, taskStatus, timestamp)
    val taskStateChange = task.update(taskStateOp)
    TaskStatusUpdateTestHelper(TaskChanged(taskStateOp, taskStateChange))
  }

  def taskExpungeFor(task: Task, taskStatus: MarathonTaskStatus, timestamp: Timestamp = defaultTimestamp) = {
    TaskStatusUpdateTestHelper(
      TaskChanged(
        TaskStateOp.MesosUpdate(task, taskStatus, timestamp),
        TaskStateChange.Expunge(task)))
  }

  def makeTaskStatus(task: Task, state: TaskState, maybeHealth: Option[Boolean] = None) = {
    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(task.taskId.mesosTaskId)
      .setState(state)
    maybeHealth.foreach(mesosStatus.setHealthy)
    MarathonTaskStatus(mesosStatus.build())
  }

  def running(task: Task = defaultTask) = taskUpdateFor(task, makeTaskStatus(task, TaskState.TASK_RUNNING))

  def runningHealthy(task: Task = defaultTask) = taskUpdateFor(task, makeTaskStatus(task, TaskState.TASK_RUNNING, maybeHealth = Some(true)))

  def runningUnhealthy(task: Task = defaultTask) = taskUpdateFor(task, makeTaskStatus(task, TaskState.TASK_RUNNING, maybeHealth = Some(false)))

  def staging(task: Task = defaultTask) = taskUpdateFor(task, makeTaskStatus(task, TaskState.TASK_STAGING))

  def finished(task: Task = defaultTask) = taskExpungeFor(task, makeTaskStatus(task, TaskState.TASK_FINISHED))

  def lost(task: Task = defaultTask) = taskExpungeFor(task, makeTaskStatus(task, TaskState.TASK_LOST))

  def killed(task: Task = defaultTask) = taskExpungeFor(task, makeTaskStatus(task, TaskState.TASK_KILLED))

  def error(task: Task = defaultTask) = taskExpungeFor(task, makeTaskStatus(task, TaskState.TASK_ERROR))
}
