package mesosphere.marathon
package core.task.bus

import mesosphere.marathon.core.instance.update.InstanceChange

trait TaskStatusEmitter {
  def publish(update: InstanceChange): Unit
}
