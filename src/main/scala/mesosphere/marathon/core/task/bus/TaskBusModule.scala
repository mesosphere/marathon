package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.impl.{
  TaskStatusEmitterImpl,
  TaskStatusObservablesImpl,
  InternalTaskStatusEventStream
}

/**
  * This module allows subscription to task updates via the taskStatusObservables. You can either subscribe to
  * updates relating to a specific app or subscribe all updates.
  *
  * The exported taskStatusEmitter has to be used to feed all events into the bus.
  */
class TaskBusModule {
  lazy val taskStatusEmitter: TaskStatusEmitter =
    new TaskStatusEmitterImpl(internalTaskStatusEventStream)
  lazy val taskStatusObservables: TaskStatusObservables =
    new TaskStatusObservablesImpl(internalTaskStatusEventStream)

  private[this] lazy val internalTaskStatusEventStream = new InternalTaskStatusEventStream()
}
