package mesosphere.marathon
package core.task.termination

import mesosphere.marathon.core.condition.Condition

// TODO(PODS): There are various similar Terminal extractors, Sets and functions – the NEED to be aligned
object InstanceChangedPredicates {

  val considerTerminal: Condition => Boolean = Set(
    // Note - these statuses are NOT terminal statuses, but a list of statuses such that if a task is seen to transition to it, consider the old task gone
    Condition.Reserved, // TODO - introduced Suspended Condition!!!
    Condition.Unreachable,
    Condition.UnreachableInactive,

    // Regular terminal statuses
    Condition.Error,
    Condition.Failed,
    Condition.Killed,
    Condition.Finished,
    Condition.Unknown,
    Condition.Gone,
    Condition.Dropped
  )
}
