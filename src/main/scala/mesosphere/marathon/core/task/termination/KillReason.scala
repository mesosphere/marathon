package mesosphere.marathon
package core.task.termination

/**
  * Enumeration for reasons why a task has been killed.
  *
  * This is not sealed on purpose as there might be different reasons for
  * components build on top of the core.
  */
trait KillReason

object KillReason {
  /** The task is killed because the instance count is scaled down */
  case object OverCapacity extends KillReason

  /** Same as [[mesosphere.marathon.core.task.termination.KillReason.OverCapacity]] but during deployment */
  case object DeploymentScaling extends KillReason

  /** The task is killed because the app has is being deleted */
  case object DeletingApp extends KillReason

  /** The task is killed because of an incoming http request */
  case object KillingTasksViaApi extends KillReason

  /** The task is killed because a new version is being deployed */
  case object Upgrading extends KillReason

  /** The task is killed because the app no longer exists */
  case object Orphaned extends KillReason

  /** The task is killed because it didn't turn running within a given time frame */
  case object Overdue extends KillReason

  /** The task is killed because it is unknown */
  case object Unknown extends KillReason

  /** The task is killed because it is not in sync with marathon state */
  case object NotInSync extends KillReason

  /** The task is killed because it exceeded the maximum number of consecutive failures */
  case object FailedHealthChecks extends KillReason
}
