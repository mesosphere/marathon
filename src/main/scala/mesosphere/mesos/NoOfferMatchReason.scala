package mesosphere.mesos

import mesosphere.mesos.protos.Resource

sealed trait NoOfferMatchReason

object NoOfferMatchReason {
  case object InsufficientMemory extends NoOfferMatchReason
  case object InsufficientCpus extends NoOfferMatchReason
  case object InsufficientDisk extends NoOfferMatchReason
  case object InsufficientGpus extends NoOfferMatchReason
  case object InsufficientPorts extends NoOfferMatchReason
  case object UnfulfilledRole extends NoOfferMatchReason
  case object UnfulfilledConstraint extends NoOfferMatchReason
  case object NoCorrespondingReservationFound extends NoOfferMatchReason

  def fromResourceType(name: String): NoOfferMatchReason = name match {
    case Resource.CPUS => InsufficientCpus
    case Resource.DISK => InsufficientDisk
    case Resource.GPUS => InsufficientGpus
    case Resource.MEM => InsufficientMemory
    case Resource.PORTS => InsufficientPorts
    case _ => throw new IllegalArgumentException(s"Not able to match $name to NoOfferMatchReason")
  }
}
