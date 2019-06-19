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
  */
case class ReviveOffersState(
    scheduledInstances: Map[Instance.Id, RunSpecConfigRef],
    terminalReservations: Set[Instance.Id],
    activeDelays: Set[RunSpecConfigRef]) extends StrictLogging {

  // At least one instance with reservations was force expunged before the reservation was destroyed.
  // This field should not be copied.
  private var forceExpungedResidentInstance: Boolean = false

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveOffersState = {
    logger.info(s"${instance.instanceId} updated to ${instance.state}")
    if (instance.isScheduled) {
      logger.info(s"Adding ${instance.instanceId} to scheduled instances.")
      copy(scheduledInstances = scheduledInstances.updated(instance.instanceId, instance.runSpec.configRef))
    } else if (!instance.isScheduled) {
      logger.info(s"Removing ${instance.instanceId} from scheduled instances.")
      copy(scheduledInstances = scheduledInstances - instance.instanceId)
    } else if (ReviveOffersState.shouldUnreserve(instance)) copy(terminalReservations = terminalReservations + instance.instanceId)
    else this
  }

  /** @return this state with passed instance removed from [[scheduledInstances]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveOffersState = {
    logger.info(s"${instance.instanceId} deleted.")
    val copied = copy(scheduledInstances - instance.instanceId, terminalReservations - instance.instanceId)
    if (instance.reservation.nonEmpty) {
      logger.info(s"Resident ${instance.instanceId} was force expunged.")
      copied.forceExpungedResidentInstance = true
    }
    copied
  }

  /** @return this state with removed ref from [[activeDelays]]. */
  def withoutDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.info(s"Marking $ref as no longer actively delayed")
    copy(activeDelays = activeDelays - ref)
  }

  /** @return this state with updated [[activeDelays]]. */
  def withDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.info(s"Marking $ref as actively delayed")
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

  /**
    * A resident instances was deleted and requires a revive.
    *
    * Currently Marathon uses [[InstanceTrackerDelegate.forceExpunge]] when a run spec with resident instances
    * is removed. Thus Marathon looses all knowledge of any reservations to these instances. The [[OfferMatcherReconciler]]
    * is supposed to filter offers for these reservations and destroy them if no related instance is known.
    *
    * A revive call to trigger an offer with said reservations to be destroyed should be emitted. There is no
    * guarantee that the reservation is destroyed.
    */
  def shouldReconcileReservation: Boolean = forceExpungedResidentInstance
}

object ReviveOffersState {
  def apply(snapshot: InstancesSnapshot): ReviveOffersState = {
    ReviveOffersState(
      snapshot.instances.view.filter(_.isScheduled).map(i => i.instanceId -> i.runSpec.configRef).toMap,
      snapshot.instances.view.filter(shouldUnreserve).map(_.instanceId).toSet,
      Set.empty
    )
  }

  /** @return whether the instance has a reservation that can be freed. */
  def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }
}