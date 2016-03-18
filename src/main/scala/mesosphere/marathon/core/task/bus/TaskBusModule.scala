package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.impl.{
  TaskStatusEmitterImpl,
  TaskChangeObservablesImpl,
  InternalTaskChangeEventStream
}

/**
  * This module allows subscription to task updates via the taskStatusObservables. You can either subscribe to
  * updates relating to a specific app or subscribe all updates.
  *
  * The exported taskStatusProcessor has to be used to feed all events into the bus.
  */
class TaskBusModule {
  lazy val taskStatusEmitter: TaskStatusEmitter =
    new TaskStatusEmitterImpl(internalTaskStatusEventStream)
  lazy val taskStatusObservables: TaskChangeObservables =
    new TaskChangeObservablesImpl(internalTaskStatusEventStream)

  private[this] lazy val internalTaskStatusEventStream = new InternalTaskChangeEventStream()
}
