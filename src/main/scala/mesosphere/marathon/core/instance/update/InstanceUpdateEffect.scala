package mesosphere.marathon.core.instance.update

import mesosphere.marathon.core.instance.Instance

/** The state change effect after applying a state change operation to the instance */
sealed trait InstanceUpdateEffect extends Product with Serializable

object InstanceUpdateEffect {
  /** The instance must be updated with the given state */
  case class Update(instance: Instance, oldState: Option[Instance]) extends InstanceUpdateEffect

  /** The instance with the given Id must be expunged */
  case class Expunge(instance: Instance) extends InstanceUpdateEffect

  /** The state if the instance didn't change */
  // TODO(PODS): do we need the id?
  case class Noop(instanceId: Instance.Id) extends InstanceUpdateEffect

  /** The state operation couldn't be applied to the given instance */
  case class Failure(cause: Throwable) extends InstanceUpdateEffect
  object Failure {
    def apply(message: String): Failure = new Failure(new RuntimeException(message))
  }
}
