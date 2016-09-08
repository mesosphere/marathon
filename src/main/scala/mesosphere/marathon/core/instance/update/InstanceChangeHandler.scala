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
  /** The affected [[Instance]] */
  val instance: Instance
  /** Id of the affected [[Instance]] */
  val id: Instance.Id = instance.id
  /** Status of the [[Instance]] */
  // TODO(PODS): We might want to transport health information in the status
  // TODO(PODS): who carries the version? instance or InstanceStatus?
  val status: InstanceStatus = instance.state.status
  /** Id of the related [[mesosphere.marathon.state.RunSpec]] */
  val runSpecId: PathId = id.runSpecId
}

/** The given instance has been created. */
case class InstanceCreated(instance: Instance) extends InstanceChange
/** The given instance has been created. */
case class InstanceUpdated(instance: Instance) extends InstanceChange
/** The given instance has been deleted. */
case class InstanceDeleted(instance: Instance) extends InstanceChange
