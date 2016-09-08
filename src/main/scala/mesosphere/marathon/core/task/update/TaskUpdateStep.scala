package mesosphere.marathon.core.task.update

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged

import scala.concurrent.Future

/**
  * A consumer interested in processing task updates.
  *
  * There is a list of these which is called in sequence by the TaskStateOpProcessor for every update.
  */
// TODO(PODS): remove trait, replaced by InstanceChangeHandler
trait TaskUpdateStep {
  def name: String

  def processUpdate(taskChanged: TaskChanged): Future[_]
}
