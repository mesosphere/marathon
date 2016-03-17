package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import org.slf4j.LoggerFactory

private[bus] class TaskStatusEmitterImpl(internalTaskStatusEventStream: InternalTaskStatusEventStream)
    extends TaskStatusEmitter {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def publish(update: TaskUpdate): Unit = {
    update.stateOp match {
      case TaskStateOp.MesosUpdate(task, status, timestamp) =>
        log.debug("publishing update {}", update)
        internalTaskStatusEventStream.publish(update)

      case _ =>
      // ignore
    }
  }
}
