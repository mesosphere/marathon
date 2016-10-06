package mesosphere.marathon.core.instance.update

import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.Instance

import scala.collection.immutable.Seq

/** The state change effect after applying a state change operation to the instance */
sealed trait InstanceUpdateEffect extends Product with Serializable

object InstanceUpdateEffect {
  /** The instance must be updated with the given state */
  case class Update(
    instance: Instance,
    oldState: Option[Instance],
    events: Seq[MarathonEvent]) extends InstanceUpdateEffect

  /** The instance with the given Id must be expunged */
  case class Expunge(
    instance: Instance,
    events: Seq[MarathonEvent]) extends InstanceUpdateEffect

  /** The state if the instance didn't change */
  case class Noop(instanceId: Instance.Id) extends InstanceUpdateEffect

  /** The state operation couldn't be applied to the given instance */
  case class Failure(cause: Throwable) extends InstanceUpdateEffect
  object Failure {
    def apply(message: String): Failure = new Failure(new RuntimeException(message))
  }
}
