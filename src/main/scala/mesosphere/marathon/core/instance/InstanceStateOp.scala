package mesosphere.marathon.core.instance

trait InstanceStateOp {
  def instanceId: Instance.Id

  /**
    * The possible instance state if processing the state op succeeds. If processing the
    * state op fails, this state will never be persisted, so be cautious when using it.
    */
  def possibleNewState: Option[Instance] = None
}

object InstanceStateOp {

  /** Expunge an instance whose InstanceOp was rejected */
  case class ForceExpunge(instanceId: Instance.Id) extends InstanceStateOp
}
