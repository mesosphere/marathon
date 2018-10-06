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
  /** The task is killed because of an incoming http request */
  case object KillingTasksViaApi extends KillReason

  /** The task is killed because it wasn't confirmed to be staging, starting, or running within a timeout */
  case object Overdue extends KillReason

  /** The task is killed because it is unknown */
  case object Unknown extends KillReason

  /** The task is killed because the instance is associated with a different taskId */
  case object NotInSync extends KillReason

  /** The task is killed because it exceeded the maximum number of consecutive health check failures */
  case object FailedHealthChecks extends KillReason
}
