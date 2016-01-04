package mesosphere.marathon.core.task.update

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * A consumer interested in processing task status updates.
  *
  * There is a list of these which is called in sequence by the TaskStatusUpdateProcessor for every update.
  */
trait TaskStatusUpdateStep {
  def name: String

  def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, mesosStatus: TaskStatus): Future[_]
}
