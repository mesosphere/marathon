package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * An operation which relates to a task and is send to Mesos for execution in an `acceptOffers` API call.
  */
sealed trait TaskOp {
  /** The ID of the affected task. */
  def taskId: Task.Id = stateOp.taskId
  /** The MarathonTask state before this operation has been applied. */
  def oldTask: Option[Task]
  /** The TaskStateOp that will lead to the new state after this operation has been applied. */
  def stateOp: TaskStateOp
  /** How would the offer change when Mesos executes this op? */
  def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer
  /** To which Offer.Operations does this task op relate? */
  def offerOperations: Iterable[org.apache.mesos.Protos.Offer.Operation]
}

object TaskOp {
  /** Launch a task on the offer. */
  case class Launch(
      taskInfo: MesosProtos.TaskInfo,
      stateOp: TaskStateOp,
      oldTask: Option[Task] = None,
      offerOperations: Iterable[MesosProtos.Offer.Operation]) extends TaskOp {

    def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer = {
      import scala.collection.JavaConverters._
      ResourceUtil.consumeResourcesFromOffer(offer, taskInfo.getResourcesList.asScala)
    }
  }

  case class ReserveAndCreateVolumes(
      stateOp: TaskStateOp.Reserve,
      resources: Iterable[MesosProtos.Resource],
      localVolumes: Iterable[LocalVolume],
      offerOperations: Iterable[MesosProtos.Offer.Operation]) extends TaskOp {

    // if the TaskOp is reverted, there should be no old state
    override def oldTask: Option[Task] = None

    override def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer =
      ResourceUtil.consumeResourcesFromOffer(offer, resources)
  }

  case class UnreserveAndDestroyVolumes(
      stateOp: TaskStateOp,
      resources: Iterable[MesosProtos.Resource],
      oldTask: Option[Task] = None) extends TaskOp {

    override lazy val offerOperations: Iterable[MesosProtos.Offer.Operation] = {
      val (withDisk, withoutDisk) = resources.partition(_.hasDisk)
      val reservationsForDisks = withDisk.map(_.toBuilder.clearDisk().build())

      import scala.collection.JavaConverters._

      val maybeDestroyVolumes: Option[MesosProtos.Offer.Operation] =
        if (withDisk.nonEmpty) {
          val destroyOp =
            MesosProtos.Offer.Operation.Destroy.newBuilder()
              .addAllVolumes(withDisk.asJava)

          val op =
            MesosProtos.Offer.Operation.newBuilder()
              .setType(MesosProtos.Offer.Operation.Type.DESTROY)
              .setDestroy(destroyOp)
              .build()

          Some(op)
        }
        else None

      val maybeUnreserve: Option[MesosProtos.Offer.Operation] =
        if (withDisk.nonEmpty || reservationsForDisks.nonEmpty) {
          val unreserveOp = MesosProtos.Offer.Operation.Unreserve.newBuilder()
            .addAllResources(withoutDisk.asJava)
            .addAllResources(reservationsForDisks.asJava)
            .build()
          val op =
            MesosProtos.Offer.Operation.newBuilder()
              .setType(MesosProtos.Offer.Operation.Type.UNRESERVE)
              .setUnreserve(unreserveOp)
              .build()
          Some(op)
        }
        else None

      Iterable(maybeDestroyVolumes, maybeUnreserve).flatten
    }

    override def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer =
      ResourceUtil.consumeResourcesFromOffer(offer, resources)
  }
}
