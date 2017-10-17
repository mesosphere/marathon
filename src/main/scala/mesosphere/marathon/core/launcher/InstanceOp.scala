package mesosphere.marathon
package core.launcher

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.ResourceUtil
import mesosphere.marathon.tasks.ResourceUtil.RichResource
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * An operation which relates to an instance and is send to Mesos for execution in an `acceptOffers` API call.
  */
sealed trait InstanceOp {
  /** The ID of the affected instance. */
  def instanceId: Instance.Id = stateOp.instanceId
  /** The instance's state before this operation has been applied. */
  def oldInstance: Option[Instance]
  /** The state operation that will lead to the new state after this operation has been applied. */
  def stateOp: InstanceUpdateOperation
  /** How would the offer change when Mesos executes this op? */
  def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer
  /** Which Offer.Operations are needed to apply this instance op? */
  def offerOperations: Seq[org.apache.mesos.Protos.Offer.Operation]
}

object InstanceOp {
  /** Launch an instance on the offer. */
  case class LaunchTask(
      taskInfo: MesosProtos.TaskInfo,
      stateOp: InstanceUpdateOperation,
      oldInstance: Option[Instance] = None,
      offerOperations: Seq[MesosProtos.Offer.Operation]) extends InstanceOp {

    def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer = {
      ResourceUtil.consumeResourcesFromOffer(offer, taskInfo.getResourcesList.toSeq)
    }
  }

  case class LaunchTaskGroup(
      executorInfo: MesosProtos.ExecutorInfo,
      groupInfo: MesosProtos.TaskGroupInfo,
      stateOp: InstanceUpdateOperation,
      oldInstance: Option[Instance] = None,
      offerOperations: Seq[MesosProtos.Offer.Operation]) extends InstanceOp {

    override def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer = {
      val taskResources: Seq[MesosProtos.Resource] =
        groupInfo.getTasksList.flatMap(_.getResourcesList)(collection.breakOut)
      val executorResources: Seq[MesosProtos.Resource] = executorInfo.getResourcesList.toSeq
      ResourceUtil.consumeResourcesFromOffer(offer, taskResources ++ executorResources)
    }
  }

  case class ReserveAndCreateVolumes(
      stateOp: InstanceUpdateOperation.Reserve,
      resources: Seq[MesosProtos.Resource],
      offerOperations: Seq[MesosProtos.Offer.Operation]) extends InstanceOp {

    // if the TaskOp is reverted, there should be no old state
    override def oldInstance: Option[Instance] = None

    override def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer =
      ResourceUtil.consumeResourcesFromOffer(offer, resources)
  }

  case class UnreserveAndDestroyVolumes(
      stateOp: InstanceUpdateOperation,
      resources: Seq[MesosProtos.Resource],
      oldInstance: Option[Instance] = None) extends InstanceOp {

    override lazy val offerOperations: Seq[MesosProtos.Offer.Operation] = {
      val (withDisk, withoutDisk) = resources.partition(_.hasDisk)
      val reservationsForDisks = withDisk.map { resource =>
        val resourceBuilder = resource.toBuilder
        // If non-root disk resource, we want to clear ALL fields except for the field indicating the disk source.
        resource.getDiskSourceOption match {
          case Some(source) =>
            resourceBuilder.setDisk(
              MesosProtos.Resource.DiskInfo.newBuilder.
                setSource(source))
          case None =>
            resourceBuilder.clearDisk()
        }

        resourceBuilder.build()
      }

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
        } else None

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
        } else None

      Seq(maybeDestroyVolumes, maybeUnreserve).flatten
    }

    override def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer =
      ResourceUtil.consumeResourcesFromOffer(offer, resources)
  }
}
