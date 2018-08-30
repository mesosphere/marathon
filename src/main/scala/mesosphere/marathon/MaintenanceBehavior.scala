package mesosphere.marathon

sealed trait MaintenanceBehavior
object MaintenanceBehavior {
  case object DeclineOffers extends MaintenanceBehavior
  case object Disabled extends MaintenanceBehavior
}
