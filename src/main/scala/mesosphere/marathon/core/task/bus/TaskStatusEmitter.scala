package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged

trait TaskStatusEmitter {
  def publish(taskChanged: TaskChanged)
}
