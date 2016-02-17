package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to crate for given app definitions and offers. */
trait TaskOpLogic {

  /**
    * Return a TaskOp if and only if the offer matches the app.
    */
  def inferTaskOp(app: AppDefinition, offer: Mesos.Offer, runningTasks: Iterable[Task]): Option[TaskOp]

}
