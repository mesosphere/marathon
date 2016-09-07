package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.instance.{Instance, InstanceStateOp}
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.TaskStateChange
import mesosphere.marathon.state.PathId
import rx.lang.scala.Observable

object TaskChangeObservables {
  case class TaskChanged(stateOp: InstanceStateOp, stateChange: TaskStateChange) {
    def taskId: Instance.Id = stateOp.instanceId
    def runSpecId: PathId = stateOp.instanceId.runSpecId
  }
}

/**
  * Allows subscription to TaskChanged updates.
  */
trait TaskChangeObservables {
  def forAll: Observable[TaskChanged]
  def forRunSpecId(appId: PathId): Observable[TaskChanged]
}

