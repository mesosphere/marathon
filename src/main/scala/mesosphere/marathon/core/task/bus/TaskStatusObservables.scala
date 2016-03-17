package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }
import rx.lang.scala.Observable

object TaskStatusObservables {
  case class TaskStatusUpdate(timestamp: Timestamp, taskId: Task.Id, status: MarathonTaskStatus) {
    def appId: PathId = taskId.appId
  }
  case class TaskUpdate(stateOp: TaskStateOp, stateChange: TaskStateChange) {
    def taskId: Task.Id = stateOp.taskId
    def appId: PathId = stateOp.taskId.appId
  }
}

/**
  * Allows subscription to TaskStatus updates.
  */
trait TaskStatusObservables {
  def forAll: Observable[TaskUpdate]
  def forAppId(appId: PathId): Observable[TaskUpdate]
}

