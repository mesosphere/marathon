package mesosphere.marathon.core.instance.update

import akka.Done
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.Future

/**
  * A consumer interested in instance change events.
  *
  * [[InstanceChange]]s will be processed in order sequentially by the
  * [[mesosphere.marathon.core.task.tracker.TaskStateOpProcessor]] for every change
  * after the change has been persisted.
  */
// TODO(PODS): rename to InstanceUpdateHandler for consistency
trait InstanceChangeHandler {
  def name: String
  def process(update: InstanceChange): Future[Done]
}

/**
  * An event notifying of an [[Instance]] change.
  */
sealed trait InstanceChange extends Product with Serializable {
  /** The affected [[Instance]] */
  val instance: Instance
  /** Id of the affected [[Instance]] */
  val id: Instance.Id = instance.instanceId
  /** version of the related run spec */
  val runSpecVersion: Timestamp = instance.runSpecVersion
  /** Status of the [[Instance]] */
  val status: InstanceStatus = instance.state.status
  /** Id of the related [[mesosphere.marathon.state.RunSpec]] */
  val runSpecId: PathId = id.runSpecId
  /** the previous state of this instance */
  def lastState: Option[InstanceState]
  /** Events that should be published for this change */
  def events: Seq[MarathonEvent]
}

/** The given instance has been created or updated. */
case class InstanceUpdated(
  instance: Instance,
  lastState: Option[InstanceState],
  events: Seq[MarathonEvent]) extends InstanceChange

/** The given instance has been deleted. */
case class InstanceDeleted(
  instance: Instance,
  lastState: Option[InstanceState],
  events: Seq[MarathonEvent]) extends InstanceChange
