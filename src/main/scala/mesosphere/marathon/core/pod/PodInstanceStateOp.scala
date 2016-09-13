package mesosphere.marathon.core.pod

import mesosphere.marathon.core.instance.{ Instance, InstanceStateOp }

sealed trait PodInstanceStateOp extends InstanceStateOp

object PodInstanceStateOp {

  case class LaunchedEphemeral(podInstance: PodInstance) extends PodInstanceStateOp {
    override val instanceId: Instance.Id = podInstance.id
    override def possibleNewState: Option[Instance] = Some(podInstance)
  }
}
