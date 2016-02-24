package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to crate for given app definitions and offers. */
trait TaskOpFactory {

  /**
    * Return a TaskOp if and only if the offer matches the app.
    * @param app
    * @param offer
    * @param tasks a list of tasks for the given app, needed to check constraints handle resident tasks
    * @return
    */
  def inferTaskOp(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp]

}
