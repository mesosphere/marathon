package mesosphere.marathon
package core.launchqueue.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.RunSpecConfigRef

/**
  * Holds the current state and defines the revive logic.
  *
  * @param scheduledInstances All scheduled instance requiring offers and their run spec ref.
  * @param terminalReservations Ids of terminal resident instance with [[Goal.Decommissioned]].
  * @param activeDelays Delays for run specs.
  * @param forceExpungedResidentInstances Counts how many resident instances have been force expunged.
  */
case class ReviveOffersState(
    scheduledInstances: Map[Instance.Id, RunSpecConfigRef],
    terminalReservations: Set[Instance.Id],
    activeDelays: Set[RunSpecConfigRef],
    forceExpungedResidentInstances: Long
) extends StrictLogging {

  /** whether the instance has a reservation that should be freed. */
  private def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }

  def withSnapshot(snapshot: InstancesSnapshot): ReviveOffersState = {
    copy(
      scheduledInstances = snapshot.instances.view.filter(_.isScheduled).map(i => i.instanceId -> i.runSpec.configRef).toMap,
      terminalReservations = snapshot.instances.view.filter(shouldUnreserve).map(_.instanceId).toSet
    )
  }

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveOffersState = {
    logger.debug(s"${instance.instanceId} updated to ${instance.state}")
    if (instance.isScheduled) {
      logger.debug(s"Adding ${instance.instanceId} to scheduled instances.")
      copy(scheduledInstances = scheduledInstances.updated(instance.instanceId, instance.runSpec.configRef))
    } else if (shouldUnreserve(instance)) {
      logger.debug(s"$instance is terminal but has a reservation.")
      copy(scheduledInstances = scheduledInstances - instance.instanceId, terminalReservations = terminalReservations + instance.instanceId)
    } else if (!instance.isScheduled) {
      logger.debug(s"Removing ${instance.instanceId} from scheduled instances.")
      copy(scheduledInstances = scheduledInstances - instance.instanceId)
    } else this
  }

  /** @return this state with passed instance removed from [[scheduledInstances]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveOffersState = {
    logger.debug(s"${instance.instanceId} deleted.")
    if (instance.reservation.nonEmpty) {
      logger.debug(s"Resident ${instance.instanceId} was force expunged.")
      copy(
        scheduledInstances - instance.instanceId,
        terminalReservations - instance.instanceId,
        forceExpungedResidentInstances = forceExpungedResidentInstances + 1
      )
    } else {
      copy(scheduledInstances - instance.instanceId, terminalReservations - instance.instanceId)
    }
  }

  /** @return this state with removed ref from [[activeDelays]]. */
  def withoutDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.debug(s"Marking $ref as no longer actively delayed")
    copy(activeDelays = activeDelays - ref)
  }

  /** @return this state with updated [[activeDelays]]. */
  def withDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.debug(s"Marking $ref as actively delayed")
    copy(activeDelays = activeDelays + ref)
  }

  /** scheduled instances that should be launched. */
  lazy val scheduledInstancesWithoutBackoff: Set[Instance.Id] =
    scheduledInstances.view.filter(kv => launchAllowed(kv._2)).map(_._1).toSet

  /** @return true if a instance has no active delay. */
  def launchAllowed(ref: RunSpecConfigRef): Boolean = {
    !activeDelays.contains(ref)
  }

  /** @return true there are no scheduled instances nor terminal instances with reservations. */
  def isEmpty: Boolean = scheduledInstancesWithoutBackoff.isEmpty && terminalReservations.isEmpty
}

object ReviveOffersState {
  def empty = ReviveOffersState(Map.empty, Set.empty, Set.empty, 0)
}
