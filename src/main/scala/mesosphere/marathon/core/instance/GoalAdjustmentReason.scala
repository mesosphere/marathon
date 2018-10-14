package mesosphere.marathon
package core.instance

/**
  * Enumeration for the reason why an instance's goal has been adjusted.
  */
sealed trait GoalAdjustmentReason extends Product with Serializable

object GoalAdjustmentReason {
  /** The instance is killed because the instance count is scaled down */
  case object OverCapacity extends GoalAdjustmentReason

  /** Same as [[mesosphere.marathon.core.instance.GoalAdjustmentReason.OverCapacity]] but during deployment */
  case object DeploymentScaling extends GoalAdjustmentReason

  /** The instance is killed because the app has is being deleted */
  case object DeletingApp extends GoalAdjustmentReason

  /** The instance is decommissioned because of an incoming http request */
  case object UserRequest extends GoalAdjustmentReason

  /** The instance is killed because a new version is being deployed */
  case object Upgrading extends GoalAdjustmentReason

  /** The instance is killed because the app no longer exists */
  case object Orphaned extends GoalAdjustmentReason

  /** The instance is killed because it didn't turn running within a given time frame */
  case object Overdue extends GoalAdjustmentReason
}
