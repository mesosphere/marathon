package mesosphere.marathon
package core.launchqueue.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersState.{HungryInstance, ReviveReason}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.VersionedRoleState

/**
  * Holds the current state and defines the revive logic.
  *
  * @param hungryInstances All scheduled instance requiring offers and their run spec ref.
  * @param activeDelays    Delays for run specs.
  */
case class ReviveOffersState(
    hungryInstances: Map[String, Map[Instance.Id, HungryInstance]],
    activeDelays: Set[RunSpecConfigRef],
    version: Long) extends StrictLogging {

  /** whether the instance has a reservation that should be freed. */
  private def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }

  private def copyBumpingVersion(
    hungryInstances: Map[String, Map[Instance.Id, HungryInstance]] = hungryInstances,
    activeDelays: Set[RunSpecConfigRef] = activeDelays): ReviveOffersState = {

    copy(hungryInstances, activeDelays, version + 1)
  }

  def withSnapshot(snapshot: InstancesSnapshot): ReviveOffersState = {
    val hungryInstances = snapshot.instances.groupBy(_.role).map {
      case (role, instances) =>
        role -> instances.view.filter(isHungry).map { i => i.instanceId -> toHungryInstance(i) }.toMap
    }
    // Note - we take all known roles, whether offers are wanted or not, and create at least an empty map entry in the hungryInstances map
    copyBumpingVersion(hungryInstances = hungryInstances)
  }

  private def hasHungryInstance(role: String, instanceId: Instance.Id): Boolean = {
    hungryInstances.getOrElse(role, Map.empty).contains(instanceId)
  }

  private def updateInstanceState(role: String, instanceId: Instance.Id, newState: Option[Instance]): ReviveOffersState = {
    val newHungryState = newState.filter(isHungry).map(toHungryInstance)

    val newHungryInstances: Map[String, Map[Instance.Id, HungryInstance]] = newHungryState match {
      case Some(hungryInstance) =>
        hungryInstance.reason match {
          case ReviveReason.Launching =>
            logger.debug(s"Adding ${instanceId} to scheduled instances.")
          case ReviveReason.CleaningUpReservations =>
            logger.debug(s"$instanceId is terminal but has a reservation.")
        }
        val newRoleHungryInstances = hungryInstances.getOrElse(role, Map.empty) + (instanceId -> hungryInstance)
        hungryInstances + (role -> newRoleHungryInstances)
      case None =>
        if (hasHungryInstance(role, instanceId))
          logger.debug(s"Removing ${instanceId} from instances wanting offers.")
        val newRoleHungryInstances = hungryInstances.getOrElse(role, Map.empty) - instanceId

        /* we don't clean up empty entries on purpose; this allows us to continue to signal that at one point in time,
         * either via the initial snapshot or later down the road, offers were wanted for an instance. Marathon will
         * remove any totally unused roles (for which no instances are defined) when a new leader is instated
         */
        hungryInstances + (role -> newRoleHungryInstances)
    }

    copyBumpingVersion(hungryInstances = newHungryInstances)
  }

  /** @return this state updated with an instance. */
  def withInstanceAddedOrUpdated(instance: Instance): ReviveOffersState = {
    if (hasHungryInstance(instance.role, instance.instanceId)) {
      this
    } else {
      updateInstanceState(instance.role, instance.instanceId, Some(instance))
    }
  }

  private def isHungry(instance: Instance): Boolean = {
    instance.isScheduled || shouldUnreserve(instance)
  }

  private def toHungryInstance(instance: Instance): HungryInstance = {
    HungryInstance(
      version,
      if (shouldUnreserve(instance)) ReviveReason.CleaningUpReservations else ReviveReason.Launching,
      instance.runSpec.configRef)
  }

  /** @return this state with passed instance removed from [[hungryInstances]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveOffersState = {
    updateInstanceState(instance.role, instance.instanceId, Some(instance))
  }

  /** @return this state with removed ref from [[activeDelays]]. */
  def withoutDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.debug(s"Marking $ref as no longer actively delayed")

    // This is not optimized
    val bumpedVersions = hungryInstances.map {
      case (role, hungryInstances) =>
        role -> hungryInstances.map {
          case (instanceId, hungryInstance) =>
            if (hungryInstance.ref == ref)
              instanceId -> hungryInstance.copy(version = this.version)
            else
              instanceId -> hungryInstance
        }
    }
    copyBumpingVersion(hungryInstances = bumpedVersions, activeDelays = activeDelays - ref)
  }

  /** @return this state with updated [[activeDelays]]. */
  def withDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.debug(s"Marking $ref as actively delayed")
    copyBumpingVersion(activeDelays = activeDelays + ref)
  }

  /** scheduled instances that should be launched. */
  lazy val roleReviveVersions: Map[String, VersionedRoleState] = {
    hungryInstances.keysIterator.map { role =>
      val iterator = hungryInstances.getOrElse(role, Map.empty).values
        .iterator
        .filter(launchAllowed)

      if (iterator.isEmpty)
        role -> VersionedRoleState(version, OffersNotWanted)
      else
        role -> VersionedRoleState(iterator.map(_.version).max, OffersWanted)
    }.toMap
  }

  /** @return true if a instance has no active delay. */
  def launchAllowed(hungryInstance: HungryInstance): Boolean = {
    hungryInstance.reason == ReviveReason.CleaningUpReservations || !activeDelays.contains(hungryInstance.ref)
  }
}

object ReviveOffersState {
  def empty = ReviveOffersState(Map.empty, Set.empty, 0)

  case class HungryInstance(version: Long, reason: ReviveReason, ref: RunSpecConfigRef)

  sealed trait ReviveReason

  case object ReviveReason {

    case object CleaningUpReservations extends ReviveReason

    case object Launching extends ReviveReason

  }

}