package mesosphere.marathon.core.task.termination.impl

import mesosphere.marathon.core.event.MesosStatusUpdateEvent

private[impl] object Terminal {

  private[this] val terminalStrings = Set(
    "TASK_ERROR",
    "TASK_FAILED",
    "TASK_KILLED",
    "TASK_FINISHED",
    "TASK_LOST"
  )

  def unapply(event: MesosStatusUpdateEvent): Option[MesosStatusUpdateEvent] = {
    if (terminalStrings(event.taskStatus)) Some(event) else None
  }
}
