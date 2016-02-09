package mesosphere.marathon.core.task.update

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * A consumer interested in processing task status updates.
  *
  * There is a list of these which is called in sequence by the TaskStatusUpdateProcessor for every update.
  */
trait TaskStatusUpdateStep {
  def name: String

  def processUpdate(timestamp: Timestamp, task: Task, mesosStatus: TaskStatus): Future[_]
}
