package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import org.slf4j.LoggerFactory

private[bus] class TaskStatusEmitterImpl(internalTaskStatusEventStream: InternalTaskChangeEventStream)
    extends TaskStatusEmitter {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def publish(update: InstanceChange): Unit = {
    log.debug("publishing update {}", update)
    internalTaskStatusEventStream.publish(update)
  }
}
