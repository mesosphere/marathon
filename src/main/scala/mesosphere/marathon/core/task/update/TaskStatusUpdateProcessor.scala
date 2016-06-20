package mesosphere.marathon.core.task.update

import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

trait TaskStatusUpdateProcessor {

  /**
    * Process a task status update message.
    * @param status the status to update.
    */
  def publish(status: TaskStatus): Future[Unit]
}
