package mesosphere.marathon.core.task.termination.impl

import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.instance.InstanceStatus

// TODO(PODS): There are various similar Terminal extractors, Sets and functions – the NEED to be aligned
private[impl] object Terminal {

  private[this] val terminalStatus = Set(
    InstanceStatus.Error,
    InstanceStatus.Failed,
    InstanceStatus.Killed,
    InstanceStatus.Finished,
    InstanceStatus.Unreachable,
    InstanceStatus.Unknown,
    InstanceStatus.Gone,
    InstanceStatus.Dropped
  )

  def unapply(event: InstanceChanged): Option[InstanceChanged] = {
    if (terminalStatus(event.status)) Some(event) else None
  }
}
