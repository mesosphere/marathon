package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersState.Role
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.stream.{RateLimiterFlow, TimedEmitter}

import scala.concurrent.duration._

object ReviveOffersStreamLogic extends StrictLogging {

  sealed trait DelayedStatus

  case class Delayed(element: RunSpecConfigRef) extends DelayedStatus

  case class NotDelayed(element: RunSpecConfigRef) extends DelayedStatus

  /**
    * Watches a stream of rate limiter updates and emits Active(configRef) when a configRef has an active backoff delay,
    * and Inactive(configRef) when it doesn't any longer.
    *
    * This allows us to receive an event when a delay's deadline expires, an removes the concern of dealing with timers
    * from the rate limiting logic itself.
    */
  val activelyDelayedRefs: Flow[RateLimiter.DelayUpdate, DelayedStatus, NotUsed] = Flow[RateLimiter.DelayUpdate]
    .map { delayUpdate =>
      val deadline = delayUpdate.delay.map(_.deadline.toInstant)
      delayUpdate.ref -> deadline
    }
    .via(TimedEmitter.flow)
    .map {
      case TimedEmitter.Active(ref) => Delayed(ref)
      case TimedEmitter.Inactive(ref) => NotDelayed(ref)
    }

  def reviveStateFromInstancesAndDelays(defaultRole: Role): Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], ReviveOffersState, NotUsed] = {
    Flow[Either[InstanceChangeOrSnapshot, DelayedStatus]].scan(ReviveOffersState.empty) {
      case (current, Left(snapshot: InstancesSnapshot)) => current.withSnapshot(snapshot, defaultRole)
      case (current, Left(InstanceUpdated(updated, _, _))) => current.withInstanceAddedOrUpdated(updated)
      case (current, Left(InstanceDeleted(deleted, _, _))) => current.withInstanceDeleted(deleted)
      case (current, Right(Delayed(configRef))) => current.withDelay(configRef)
      case (current, Right(NotDelayed(configRef))) => current.withoutDelay(configRef)
    }
  }

  /**
    * Core logic for suppress and revive
    *
    * Receives either instance updates or delay updates; based on the state of those, issues a suppress or a revive call
    *
    * Revive rate is throttled and debounced using minReviveOffersInterval
    *
    * @param minReviveOffersInterval - The maximum rate at which we allow suppress and revive commands to be applied
    * @param enableSuppress          - Whether or not to enable offer suppression
    * @return
    */
  def suppressAndReviveFlow(

    minReviveOffersInterval: FiniteDuration,
    enableSuppress: Boolean,
    defaultRole: Role): Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], RoleDirective, NotUsed] = {

    val reviveRepeaterWithTicks = Flow[RoleDirective]
      .map(Left(_))
      .merge(Source.tick(minReviveOffersInterval, minReviveOffersInterval, Right(Tick)), eagerComplete = true)
      .via(reviveRepeater)

    reviveStateFromInstancesAndDelays(defaultRole)
      .buffer(1, OverflowStrategy.dropHead) // While we are back-pressured, we drop older interim frames
      .via(RateLimiterFlow.apply(minReviveOffersInterval))
      .map(_.roleReviveVersions)
      .via(reviveDirectiveFlow(enableSuppress))
      .map(l => { logger.info(s"Issuing following suppress/revive directives: = ${l}"); l })
      .via(reviveRepeaterWithTicks)
  }

  def reviveDirectiveFlow(enableSuppress: Boolean): Flow[Map[Role, VersionedRoleState], RoleDirective, NotUsed] = {
    val logic = if (enableSuppress) new ReviveDirectiveFlowLogicWithSuppression else new ReviveDirectiveFlowLogicWithoutSuppression
    Flow[Map[Role, VersionedRoleState]]
      .sliding(2)
      .mapConcat({
        case Seq(lastState, newState) =>
          logic.directivesForDiff(lastState, newState)
        case _ =>
          logger.info(s"Revive stream is terminating")
          Nil
      })
  }

  /**
    * Immutable directive generator which compares two offers wanted state and issues the appropriate unsuppress or
    * re-revive directives.
    *
    * There are two implementations for the logic, one with suppression, and the other with suppression disabled.
    */
  private[impl] trait ReviveDirectiveFlowLogic {
    def lastOffersWantedVersion(lastState: Map[Role, VersionedRoleState], role: Role): Option[Long] =
      lastState.get(role).collect { case VersionedRoleState(version, OffersWanted) => version }

    def directivesForDiff(lastState: Map[Role, VersionedRoleState], newState: Map[Role, VersionedRoleState]): List[RoleDirective]
  }

  private[impl] class ReviveDirectiveFlowLogicWithoutSuppression extends ReviveDirectiveFlowLogic {

    def directivesForDiff(lastState: Map[Role, VersionedRoleState], newState: Map[Role, VersionedRoleState]): List[RoleDirective] = {
      val rolesChanged = lastState.keySet != newState.keySet
      val directives = List.newBuilder[RoleDirective]

      if (rolesChanged) {
        val newRoleState = newState.keysIterator.map { role =>
          role -> OffersWanted
        }.toMap
        val updateFramework = UpdateFramework(
          newRoleState,
          newlyRevived = newState.keySet -- lastState.keySet,
          newlySuppressed = Set.empty
        )
        directives += updateFramework
      }
      val needsExplicitRevive = newState.iterator
        .collect {
          case (role, VersionedRoleState(_, OffersWanted)) if !lastState.get(role).exists(_.roleState.isWanted) => role
          case (role, VersionedRoleState(version, OffersWanted)) if lastOffersWantedVersion(lastState, role).exists(_ < version) => role
        }
        .toSet

      if (needsExplicitRevive.nonEmpty)
        directives += IssueRevive(needsExplicitRevive)

      directives.result()
    }
  }

  private[impl] class ReviveDirectiveFlowLogicWithSuppression extends ReviveDirectiveFlowLogic {

    private def offersNotWantedRoles(state: Map[Role, VersionedRoleState]): Set[Role] =
      state.collect { case (role, VersionedRoleState(_, OffersNotWanted)) => role }.toSet

    def updateFrameworkNeeded(lastState: Map[Role, VersionedRoleState], newState: Map[Role, VersionedRoleState]) = {
      val rolesChanged = lastState.keySet != newState.keySet
      val suppressedChanged = offersNotWantedRoles(lastState) != offersNotWantedRoles(newState)
      rolesChanged || suppressedChanged
    }

    def directivesForDiff(lastState: Map[Role, VersionedRoleState], newState: Map[Role, VersionedRoleState]): List[RoleDirective] = {
      val directives = List.newBuilder[RoleDirective]

      if (updateFrameworkNeeded(lastState, newState)) {
        val roleState = newState.map {
          case (role, VersionedRoleState(_, state)) => role -> state
        }
        val newlyWanted = newState
          .iterator
          .collect { case (role, v) if v.roleState.isWanted && !lastState.get(role).exists(_.roleState.isWanted) => role }
          .to(Set)

        val newlyNotWanted = newState
          .iterator
          .collect { case (role, v) if !v.roleState.isWanted && lastState.get(role).exists(_.roleState.isWanted) => role }
          .to(Set)
        directives += UpdateFramework(roleState, newlyRevived = newlyWanted, newlySuppressed = newlyNotWanted)
      }

      val rolesNeedingRevive = newState.view
        .collect { case (role, VersionedRoleState(version, OffersWanted)) if lastOffersWantedVersion(lastState, role).exists(_ < version) => role }.toSet

      if (rolesNeedingRevive.nonEmpty)
        directives += IssueRevive(rolesNeedingRevive)

      directives.result()

    }
  }

  def reviveRepeater: Flow[Either[RoleDirective, Tick.type], RoleDirective, NotUsed] = Flow[Either[RoleDirective, Tick.type]]
    .statefulMapConcat { () =>
      val logic = new ReviveRepeaterLogic

      {
        case Left(directive) =>
          logic.processRoleDirective(directive)
          List(directive)

        case Right(tick) =>
          logic.handleTick()
      }
    }

  /**
    * Stateful event processor to handle the (rather complex) task of repeating revive signal based on the last directive.
    *
    * Rather than using a timer directly, ReviveRepeaterLogic repeats revive signal in response to ticks received;
    * specifically, it will indicate that offers should be revived for a role on the 2nd tick received after the initial
    * unsuppress or revive directive was received, unless if offers for the role are suppressed.
    */
  private[impl] class ReviveRepeaterLogic extends StrictLogging {
    var currentRoleState: Map[Role, RoleOfferState] = Map.empty
    var repeatIn: Map[Role, Int] = Map.empty

    def markRolesForRepeat(roles: Iterable[Role]): Unit =
      roles.foreach {
        role =>
          // Override any old state.
          repeatIn += role -> 2
      }

    def processRoleDirective(directive: RoleDirective): Unit = directive match {
      case updateFramework: UpdateFramework =>
        logger.info(s"Issuing update framework for $updateFramework")
        currentRoleState = updateFramework.roleState
        markRolesForRepeat(updateFramework.newlyRevived)

      case IssueRevive(roles) =>
        logger.info(s"Issuing revive for roles $roles")
        markRolesForRepeat(roles) // set / reset the repeat delay
    }

    def handleTick(): List[RoleDirective] = {
      // Decrease tick counts and filter out those that are zero.
      val newRepeatIn = repeatIn.collect {
        case (k, v) if v >= 1 => k -> (v - 1)
      }

      // Repeat revives for those roles that waited for a tick.
      val rolesForReviveRepetition = newRepeatIn.iterator.collect {
        case (role, counter) if counter == 0 && currentRoleState.get(role).contains(OffersWanted) => role
      }.toSet

      repeatIn = newRepeatIn

      if (rolesForReviveRepetition.isEmpty) {
        Nil
      } else {
        logger.info(s"Repeat revive for roles $rolesForReviveRepetition.")
        List(IssueRevive(rolesForReviveRepetition))
      }
    }
  }

  private[impl] case object Tick

  sealed trait RoleDirective

  /**
    *
    * @param roleState       The data specifying to which roles we should be subscribed, and which should be suppressed
    * @param newlyRevived    Convenience metadata - Set of roles that were previously non-existent or suppressed
    * @param newlySuppressed Convenience metadata - Set of roles that were previously not suppressed
    */
  case class UpdateFramework(
      roleState: Map[String, RoleOfferState],
      newlyRevived: Set[String],
      newlySuppressed: Set[String]) extends RoleDirective

  case class IssueRevive(roles: Set[String]) extends RoleDirective

  case class VersionedRoleState(version: Long, roleState: RoleOfferState)

}
