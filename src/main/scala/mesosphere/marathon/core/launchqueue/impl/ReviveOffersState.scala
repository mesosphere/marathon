package mesosphere.marathon
package core.launchqueue.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersState.{HungryInstance, ReviveReason}

/**
  * Holds the current state and defines the revive logic.
  *
  * @param hungryInstances All scheduled instance requiring offers and their run spec ref.
  * @param activeDelays    Delays for run specs.
  */
case class ReviveOffersState(
    hungryInstances: Map[String, Map[Instance.Id, HungryInstance]],
    allInstanceIdsByRole: Map[String, Set[Instance.Id]],
    activeDelays: Set[RunSpecConfigRef],
    version: Long) extends StrictLogging {

  /** whether the instance has a reservation that should be freed. */
  private def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }

  private def update(
    hungryInstances: Map[String, Map[Instance.Id, HungryInstance]] = hungryInstances,
    allInstanceIdsByRole: Map[String, Set[Instance.Id]] = allInstanceIdsByRole,
    activeDelays: Set[RunSpecConfigRef] = activeDelays): ReviveOffersState = {

    copy(hungryInstances, allInstanceIdsByRole, activeDelays, version + 1)
  }

  def withSnapshot(snapshot: InstancesSnapshot): ReviveOffersState = {
    update(
      hungryInstances = snapshot.instances.view.filter(isHungry).groupBy(_.role).map {
        case (role, instances) =>
          role -> instances.map { i => i.instanceId -> toHungryInstance(i) }.toMap
      },
      allInstanceIdsByRole = snapshot.instances.view.groupBy(_.role).map { case (role, instances) => role -> instances.map(_.instanceId).toSet })
  }

  private def hasInstance(role: String, instanceId: Instance.Id): Boolean = {
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
        if (hasInstance(role, instanceId))
          logger.debug(s"Removing ${instanceId} from instances wanting offers.")
        val newRoleHungryInstances = hungryInstances.getOrElse(role, Map.empty) - instanceId
        val newHungryInstances =
          if (newRoleHungryInstances.isEmpty)
            hungryInstances - role
          else
            hungryInstances + (role -> newRoleHungryInstances)

        newHungryInstances
    }

    val newAllInstanceIdsByRole = if (newState.nonEmpty) {
      val withInstanceIdAdded = allInstanceIdsByRole.getOrElse(role, Set.empty) + instanceId
      allInstanceIdsByRole + (role -> withInstanceIdAdded)
    } else {
      val withInstanceIdRemoved = allInstanceIdsByRole.getOrElse(role, Set.empty) - instanceId
      if (withInstanceIdRemoved.isEmpty)
        allInstanceIdsByRole - role
      else
        allInstanceIdsByRole + (role -> withInstanceIdRemoved)
    }

    update(hungryInstances = newHungryInstances, allInstanceIdsByRole = newAllInstanceIdsByRole)
  }

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveOffersState = {
    updateInstanceState(instance.role, instance.instanceId, Some(instance))
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
    update(hungryInstances = bumpedVersions, activeDelays = activeDelays - ref)
  }

  /** @return this state with updated [[activeDelays]]. */
  def withDelay(ref: RunSpecConfigRef): ReviveOffersState = {
    logger.debug(s"Marking $ref as actively delayed")
    update(activeDelays = activeDelays + ref)
  }

  /** scheduled instances that should be launched. */
  lazy val roleReviveVersions: Map[String, RoleOfferState] = {
    allInstanceIdsByRole.keysIterator.map { role =>
      role -> hungryInstances.getOrElse(role, Map.empty).values
        .iterator
        .filter(launchAllowed)
        .foldLeft(OffersNotWanted: RoleOfferState) {
          case (op @ OffersWanted(version), hungryInstance) if hungryInstance.version < version =>
            op
          case (_, hungryInstance) =>
            OffersWanted(hungryInstance.version)
        }
    }.toMap
  }

  /** @return true if a instance has no active delay. */
  def launchAllowed(hungryInstance: HungryInstance): Boolean = {
    hungryInstance.reason == ReviveReason.CleaningUpReservations || !activeDelays.contains(hungryInstance.ref)
  }
}

object ReviveOffersState {
  def empty = ReviveOffersState(Map.empty, Map.empty, Set.empty, 0)

  case class HungryInstance(version: Long, reason: ReviveReason, ref: RunSpecConfigRef)

  sealed trait ReviveReason

  case object ReviveReason {

    case object CleaningUpReservations extends ReviveReason

    case object Launching extends ReviveReason

  }

}