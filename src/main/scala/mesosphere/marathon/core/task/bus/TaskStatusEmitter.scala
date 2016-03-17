package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate

trait TaskStatusEmitter {
  def publish(update: TaskUpdate)
}
