package mesosphere.marathon.core.task.tracker

import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

trait TaskStatusUpdateProcessor {
  def publish(status: TaskStatus): Future[Unit]
}
