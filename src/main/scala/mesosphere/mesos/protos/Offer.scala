package mesosphere.mesos.protos

case class Offer(
    offerId: OfferID,
    frameworkId: FrameworkID,
    slaveId: SlaveID,
    hostname: String,
    resources: Seq[Resource],
    attributes: Seq[Attribute],
    executorIds: Seq[ExecutorID])
