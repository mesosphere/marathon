package mesosphere.marathon
package core.task.termination.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.Task

/**
  * Possible actions that can be chosen in order to `kill` a given instance.
  * Depending on the instance's state this can be one of
  * - [[KillAction.Decommission]]
  * - [[KillAction.Noop]]
  * - [[KillAction.IssueKillRequest]]
  */
private[termination] sealed trait KillAction

private[termination] object KillAction extends StrictLogging {

  /**
    * Any normal, reachable and stateless instance will simply be killed via the scheduler driver.
    */
  case object IssueKillRequest extends KillAction

  /**
    * Do nothing. This is currently what we do for unreachable tasks with reservations. See #5261
    */
  case object Noop extends KillAction

  /**
    * In case of an instance being Unreachable, killing the related Mesos task is impossible.
    * In order to get rid of the instance, we decommission this instance.
    * If the instance is reported to be non-terminal in the future, it will be killed.
    */
  case object Decommission extends KillAction

  /* returns whether or not we can expect the task to report a terminal state after sending a kill signal */
  private val wontRespondToKill: Condition => Boolean = {
    import Condition._
    Set(
      Unknown,
      Unreachable,
      UnreachableInactive,
      // TODO: it should be safe to remove these from this list, because
      // 1) all taskId's should be removed at this point, because Gone & Dropped are terminal.
      // 2) Killing a Gone / Dropped task will cause it to be in a terminal state.
      // 3) Killing a Gone / Dropped task may result in no status change at all.
      // 4) Either way, we end up in a terminal state.
      // However, we didn't want to risk changing behavior in a point release. So they remain here.
      Dropped,
      Gone
    )
  }

  /**
    * Computes the [[KillAction]] based on the instance's state.
    *
    * if the instance can't be reached, issuing a kill request won't cause the instance to progress towards a terminal
    * state; Mesos will simply re-send the current state. Our current behavior, for ephemeral, is to simply delete any
    * knowledge that the instance might be running, such that if it is reported by Mesos later we will kill it. (that
    * could be improved).
    *
    * If the instance is lost _and_ has reservations, we do nothing.
    *
    * any other case -> issue a kill request
    */
  def apply(instanceId: Instance.Id, taskIds: Iterable[Task.Id], knownInstance: Option[Instance]): KillAction = {
    val hasReservations = knownInstance.fold(false)(_.hasReservation)

    // TODO(PODS): align this with other Terminal/Unreachable/whatever extractors
    val maybeCondition = knownInstance.map(_.state.condition)
    val isUnkillable = maybeCondition.fold(false)(wontRespondToKill)
    val isGoalRunning: Boolean = knownInstance.exists(_.state.goal == Goal.Running)

    if (isUnkillable && isGoalRunning) {
      if (hasReservations) {
        logger.info(s"Ignoring kill request for $instanceId; It is in unkillable state but it has reservation - cannot decommission.")
        KillAction.Noop
      } else {
        val msg = s"its condition is ${maybeCondition.fold("unknown")(_.toString)}"
        logger.warn(s"Decommissioning $instanceId from state because $msg")
        // we will eventually be notified of a taskStatusUpdate after the instance has been expunged
        KillAction.Decommission
      }
    } else {
      val knownOrNot = if (knownInstance.isDefined) "known" else "unknown"
      logger.warn(s"Killing $knownOrNot ${taskIds.mkString(",")} of instance $instanceId")
      KillAction.IssueKillRequest
    }
  }

}
