package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to create for given app definitions and offers. */
trait TaskOpFactory {

  /**
    * @param tasks a list of tasks for the given app, needed to check constraints and handle resident tasks
    * @return a TaskOp if and only if the offer matches the app.
    */
  def buildTaskOp(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp]

}
