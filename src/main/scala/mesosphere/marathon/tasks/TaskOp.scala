package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import org.apache.mesos.{ Protos => Mesos }

/**
  * An operation which relates to a task and is send to Mesos for execution in an `acceptOffers` API call.
  */
sealed trait TaskOp {
  /** The ID of the affected task. */
  def taskId: Task.Id = newTask.taskId
  /** The MarathonTask state before this operation has been applied. */
  def oldTask: Option[Task]
  /** The MarathonTask state after this operation has been applied. */
  def newTask: Task
  /** How would the offer change when Mesos executes this op? */
  def applyToOffer(offer: Mesos.Offer): Mesos.Offer
  /** To which Offer.Operations does this task op relate? */
  def offerOperations: Iterable[org.apache.mesos.Protos.Offer.Operation]
}

object TaskOp {
  /** Launch a task on the offer. */
  case class Launch(
      taskInfo: Mesos.TaskInfo,
      newTask: Task,
      oldTask: Option[Task] = None,
      offerOperations: Iterable[org.apache.mesos.Protos.Offer.Operation]) extends TaskOp {

    def applyToOffer(offer: Mesos.Offer): Mesos.Offer = {
      import scala.collection.JavaConverters._
      ResourceUtil.consumeResourcesFromOffer(offer, taskInfo.getResourcesList.asScala)
    }
  }

  case class ReserveAndCreateVolumes(
      newTask: Task,
      resources: Iterable[Mesos.Resource],
      localVolumes: Iterable[LocalVolume],
      oldTask: Option[Task] = None,
      offerOperations: Iterable[Mesos.Offer.Operation]) extends TaskOp {

    override def applyToOffer(offer: Mesos.Offer): Mesos.Offer =
      ResourceUtil.consumeResourcesFromOffer(offer, resources)
  }

}
