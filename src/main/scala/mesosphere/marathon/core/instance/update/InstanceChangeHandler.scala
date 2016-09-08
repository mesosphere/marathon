package mesosphere.marathon.core.instance.update

import akka.Done
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

/**
  * A consumer interested in instance change events.
  *
  * [[InstanceChange]]s will be processed in order sequentially by the
  * [[mesosphere.marathon.core.task.tracker.TaskStateOpProcessor]] for every change
  * after the change has been persisted.
  */
trait InstanceChangeHandler {
  def name: String
  def process(update: InstanceChange): Future[Done]
}

/**
  * An event notifying of an [[Instance]] change.
  */
sealed trait InstanceChange {
  /** Id of the affected [[Instance]] */
  def id: Instance.Id
  /** Status of the [[Instance]] */
  // TODO(PODS): We might want to transport health information in the status
  def status: InstanceStatus
  /** Id of the related [[mesosphere.marathon.state.RunSpec]] */
  final def runSpecId: PathId = id.runSpecId
}

/** The given instance has been created. */
case class InstanceCreated(id: Instance.Id, status: InstanceStatus, instance: Instance) extends InstanceChange
/** The given instance has been created. */
case class InstanceUpdated(id: Instance.Id, status: InstanceStatus, instance: Instance) extends InstanceChange
/** The given instance has been deleted. */
case class InstanceDeleted(id: Instance.Id, status: InstanceStatus) extends InstanceChange
