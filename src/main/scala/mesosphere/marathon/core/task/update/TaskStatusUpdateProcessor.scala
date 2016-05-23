package mesosphere.marathon.core.task.update

import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

trait TaskStatusUpdateProcessor {

  /**
    * Process a task status update message.
    * @param status the status to update.
    * @param ack if this update is coming from Mesos, you want to acknowledge this update.
    */
  def publish(status: TaskStatus, ack: Boolean = true): Future[Unit]
}
