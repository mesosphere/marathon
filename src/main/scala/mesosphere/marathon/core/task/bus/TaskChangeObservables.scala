package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.PathId
import rx.lang.scala.Observable

object TaskChangeObservables {
  case class TaskChanged(stateOp: TaskStateOp, stateChange: TaskStateChange) {
    def taskId: Task.Id = stateOp.taskId
    def appId: PathId = stateOp.taskId.appId
  }
}

/**
  * Allows subscription to TaskChanged updates.
  */
trait TaskChangeObservables {
  def forAll: Observable[TaskChanged]
  def forAppId(appId: PathId): Observable[TaskChanged]
}

