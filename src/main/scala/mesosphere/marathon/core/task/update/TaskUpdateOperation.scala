package mesosphere.marathon.core.task.update

import mesosphere.marathon.core.instance.InstanceStatus
import org.apache.mesos

/** Trait for operations attempting to change the state of a task */
trait TaskUpdateOperation extends Product with Serializable

object TaskUpdateOperation {
  case class MesosUpdate(status: InstanceStatus, taskStatus: mesos.Protos.TaskStatus) extends TaskUpdateOperation
}
