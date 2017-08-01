package mesosphere.marathon
package core.task.update

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

/** Trait for operations attempting to change the state of a task */
trait TaskUpdateOperation extends Product with Serializable

object TaskUpdateOperation {
  case class MesosUpdate(
    condition: Condition,
    taskStatus: mesos.Protos.TaskStatus,
    now: Timestamp) extends TaskUpdateOperation

  case class LaunchOnReservation(
    newTaskId: Task.Id,
    runSpecVersion: Timestamp,
    status: Task.Status) extends TaskUpdateOperation
}
