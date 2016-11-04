package mesosphere.mesos

trait NoOfferMatchReason

object NoOfferMatchReason {
  case object InsufficientMemory extends NoOfferMatchReason
  case object InsufficientCpus extends NoOfferMatchReason
  case object InsufficientDisk extends NoOfferMatchReason
  case object InsufficientGpus extends NoOfferMatchReason
  case object InsufficientPorts extends NoOfferMatchReason
  case object UnmatchedConstraint extends NoOfferMatchReason
}
