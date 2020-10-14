package mesosphere.marathon
package core.instance

/**
  * Enumeration for the reason why an instance's goal has been adjusted.
  */
sealed trait GoalChangeReason extends Product with Serializable

object GoalChangeReason {

  /** The goal is changed because the instance count is scaled down */
  case object OverCapacity extends GoalChangeReason

  /** Same as [[mesosphere.marathon.core.instance.GoalChangeReason.OverCapacity]] but during deployment */
  case object DeploymentScaling extends GoalChangeReason

  /** The goal is changed because the app has is being deleted */
  case object DeletingApp extends GoalChangeReason

  /** The goal is changed because of an incoming http request */
  case object UserRequest extends GoalChangeReason

  /** The goal is changed because a new version is being deployed */
  case object Upgrading extends GoalChangeReason

  /** The goal is changed because the app no longer exists */
  case object Orphaned extends GoalChangeReason

  /** The goal is changedbecause it didn't turn running within a given time frame */
  case object Overdue extends GoalChangeReason

  /**
    * We were asked to kill ephemeral instance but it is in state that won't respond to kill.
    * Decommissioning to be sure to kill this task if it comes back.
    */
  case object UnkillableEphemeralInstance extends GoalChangeReason
}
