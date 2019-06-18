package mesosphere.marathon
package core.launchqueue.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.RunSpecConfigRef

/**
  * Holds the current state and defines the revive logic.
  *
  * @param scheduledRunSpecs All run specs that have at least one scheduled instance requiring offers.
  * @param terminalReservations Ids of terminal resident instance with [[Goal.Decommissioned]].
  * @param activeDelays Delays for run specs.
  */
case class ReviveOffersState(
    scheduledRunSpecs: Set[RunSpecConfigRef],
    terminalReservations: Set[Instance.Id],
    activeDelays: Set[RunSpecConfigRef]) extends StrictLogging {

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveOffersState = {
    logger.info(s"${instance.instanceId} updated to ${instance.state}")
    if (instance.isScheduled) copy(scheduledRunSpecs = scheduledRunSpecs + instance.runSpec.configRef)
    else if (ReviveOffersState.shouldUnreserve(instance)) copy(terminalReservations = terminalReservations + instance.instanceId)
    else this
  }

  /** @return this state with passed instance removed from [[scheduledRunSpecs]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveOffersState = {
    logger.info(s"${instance.instanceId} deleted.")
    copy(scheduledRunSpecs - instance.runSpec.configRef, terminalReservations - instance.instanceId)
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

  def freeze: (Set[RunSpecConfigRef], Set[Instance.Id]) = {
    (scheduledRunSpecs.filter(launchAllowed), terminalReservations)
  }

  // ------- ALTERNATIVE ------

  /**
    * Evaluate whether we should revive or suppress based on the current state ''and'' the existence of an active
    * backoff delay.
    *
    * @return whether we should revive or suppress.
    */
  def evaluate: Op = {
    if (terminalReservations.nonEmpty || scheduledInstanceWithoutBackoffExists) {
      logger.info("Reviving offers.")
      Revive
    } else {
      logger.info("Suppressing offers.")
      Suppress
    }
  }

  /** @return true if there is at least one scheduled instance that has no active backoff. */
  def scheduledInstanceWithoutBackoffExists: Boolean = {
    scheduledRunSpecs.exists(launchAllowed(_))
  }

  /** @return true if a instance has no active delay. */
  def launchAllowed(ref: RunSpecConfigRef): Boolean = {
    activeDelays.contains(ref)
  }
}

object ReviveOffersState {
  def apply(snapshot: InstancesSnapshot): ReviveOffersState = {
    ReviveOffersState(
      snapshot.instances.view.filter(_.isScheduled).map(i => i.runSpec.configRef).toSet,
      snapshot.instances.view.filter(shouldUnreserve).map(_.instanceId).toSet,
      Set.empty
    )
  }

  /** @return whether the instance has a reservation that can be freed. */
  def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }
}