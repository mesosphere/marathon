package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.{ PathId, Timestamp }
import rx.lang.scala.Observable

object TaskStatusObservables {
  case class TaskStatusUpdate(timestamp: Timestamp, taskId: Task.Id, status: MarathonTaskStatus) {
    def appId: PathId = taskId.appId
  }
}

/**
  * Allows subscription to TaskStatus updates.
  */
trait TaskStatusObservables {
  def forAll: Observable[TaskStatusUpdate]
  def forAppId(appId: PathId): Observable[TaskStatusUpdate]
}

