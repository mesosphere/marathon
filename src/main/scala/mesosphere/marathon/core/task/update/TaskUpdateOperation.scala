package mesosphere.marathon.core.task.update

import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

/** Trait for operations attempting to change the state of a task */
trait TaskUpdateOperation extends Product with Serializable

object TaskUpdateOperation {
  case class MesosUpdate(
    status: InstanceStatus,
    taskStatus: mesos.Protos.TaskStatus,
    now: Timestamp) extends TaskUpdateOperation

  case class LaunchOnReservation(
    runSpecVersion: Timestamp,
    status: Task.Status,
    hostPorts: Seq[Int]) extends TaskUpdateOperation
}
