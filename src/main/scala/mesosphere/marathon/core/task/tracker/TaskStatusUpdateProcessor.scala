package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.task.bus.TaskStatusObservables

import scala.concurrent.Future

trait TaskStatusUpdateProcessor {
  def publish(status: TaskStatusObservables.TaskStatusUpdate): Future[Unit]
}
