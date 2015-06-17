package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import org.slf4j.LoggerFactory

private[bus] class TaskStatusEmitterImpl(internalTaskStatusEventStream: InternalTaskStatusEventStream)
    extends TaskStatusEmitter {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def publish(status: TaskStatusUpdate): Unit = {
    log.debug("publishing update {}", status)
    internalTaskStatusEventStream.publish(status)
  }
}
