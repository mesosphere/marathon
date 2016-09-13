package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import org.slf4j.LoggerFactory

private[bus] class TaskStatusEmitterImpl(internalTaskStatusEventStream: InternalTaskChangeEventStream)
    extends TaskStatusEmitter {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def publish(taskChanged: TaskChanged): Unit = {
    taskChanged.stateOp match {
      case _: InstanceUpdateOperation.MesosUpdate =>
        log.debug("publishing update {}", taskChanged)
        internalTaskStatusEventStream.publish(taskChanged)

      case _ =>
      // ignore
    }
  }
}
