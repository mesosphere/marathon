package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.UpdateFramework.Unsuppressed
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.stream.{EnrichedFlow, TimedEmitter}

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

  val reviveStateFromInstancesAndDelays: Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], ReviveOffersState, NotUsed] = {
    Flow[Either[InstanceChangeOrSnapshot, DelayedStatus]].scan(ReviveOffersState.empty) {
      case (current, Left(snapshot: InstancesSnapshot)) => current.withSnapshot(snapshot)
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
    enableSuppress: Boolean): Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], RoleDirective, NotUsed] = {

    val reviveRepeaterWithTicks = Flow[RoleDirective]
      .map(Left(_))
      .merge(Source.tick(minReviveOffersInterval, minReviveOffersInterval, Right(Tick)), eagerComplete = true)
      .via(reviveRepeater)

    reviveStateFromInstancesAndDelays
      .buffer(1, OverflowStrategy.dropHead) // While we are back-pressured, we drop older interim frames
      .throttle(1, minReviveOffersInterval)
      .map(_.roleReviveVersions)
      .via(reviveDirectiveFlow(enableSuppress))
      .via(reviveRepeaterWithTicks)
  }

  def reviveDirectiveFlow(enableSuppress: Boolean): Flow[Map[String, RoleOfferState], RoleDirective, NotUsed] = {
    val logic = if (enableSuppress) new ReviveDirectiveFlowLogicWithSuppression else new ReviveDirectiveFlowLogicWithoutSuppression
    Flow[Map[String, RoleOfferState]]
      .sliding(2)
      .mapConcat({
        case Seq(lastState, newState) =>
          logic.directivesForDiff(lastState, newState)
        case _ =>
          logger.debug(s"Stream is terminating")
          Nil
      })
  }

  trait ReviveDirectiveFlowLogic {
    def lastOffersWantedVersion(lastState: Map[String, RoleOfferState], role: String): Option[Long] =
      lastState.get(role).collect { case OffersWanted(version) => version }

    def directivesForDiff(lastState: Map[String, RoleOfferState], newState: Map[String, RoleOfferState]): List[RoleDirective]
  }

  class ReviveDirectiveFlowLogicWithoutSuppression extends ReviveDirectiveFlowLogic {

    def directivesForDiff(lastState: Map[String, RoleOfferState], newState: Map[String, RoleOfferState]): List[RoleDirective] = {
      val rolesChanged = lastState.keySet != newState.keySet
      var directives = List.newBuilder[RoleDirective]

      if (rolesChanged) {
        val newRoleState = newState.keysIterator.map { role =>
          role -> UpdateFramework.Unsuppressed
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
          case (role, OffersWanted(_)) if !lastState.get(role).exists(_.isWanted) => role
          case (role, OffersWanted(version)) if lastOffersWantedVersion(lastState, role).exists(_ < version) => role
        }
        .toSet

      if (needsExplicitRevive.nonEmpty)
        directives += IssueRevive(needsExplicitRevive)

      directives.result()
    }
  }

  class ReviveDirectiveFlowLogicWithSuppression extends ReviveDirectiveFlowLogic {

    private def offersNotWantedRoles(state: Map[String, RoleOfferState]): Set[String] =
      state.collect { case (role, OffersNotWanted) => role }.toSet

    def updateFrameworkNeeded(lastState: Map[String, RoleOfferState], newState: Map[String, RoleOfferState]) = {
      val rolesChanged = lastState.keySet != newState.keySet
      val suppressedChanged = offersNotWantedRoles(lastState) != offersNotWantedRoles(newState)
      rolesChanged || suppressedChanged
    }

    def directivesForDiff(lastState: Map[String, RoleOfferState], newState: Map[String, RoleOfferState]): List[RoleDirective] = {
      val rolesChanged = lastState.keySet != newState.keySet
      var directives = List.newBuilder[RoleDirective]

      if (updateFrameworkNeeded(lastState, newState)) {
        val roleState = newState.map {
          case (role, OffersWanted(_)) => role -> UpdateFramework.Unsuppressed
          case (role, OffersNotWanted) => role -> UpdateFramework.Suppressed
        }
        val newlyWanted = newState
          .iterator
          .collect { case (role, v) if v.isWanted && !lastState.get(role).exists(_.isWanted) => role }
          .to[Set]

        val newlyNotWanted = newState
          .iterator
          .collect { case (role, v) if !v.isWanted && lastState.get(role).exists(_.isWanted) => role }
          .to[Set]
        directives += UpdateFramework(roleState, newlyRevived = newlyWanted, newlySuppressed = newlyNotWanted)
      }

      val rolesNeedingRevive = newState.view
        .collect { case (role, OffersWanted(version)) if lastOffersWantedVersion(lastState, role).exists(_ < version) => role }.toSet

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

  private[impl] class ReviveRepeaterLogic {
    var currentRoleState: Map[String, UpdateFramework.RoleState] = Map.empty
    var repeatIn: Map[String, Int] = Map.empty

    def markRolesForRepeat(roles: Iterable[String]): Unit =
      roles.foreach {
        role =>
          repeatIn += role -> 2
      }

    def processRoleDirective(directive: RoleDirective): Unit = directive match {
      case updateFramework: UpdateFramework =>
        currentRoleState = updateFramework.roleState
        markRolesForRepeat(updateFramework.newlyRevived)

      case IssueRevive(roles) =>
        markRolesForRepeat(roles) // set / reset the repeat delay
    }

    def handleTick(): List[RoleDirective] = {
      val newRepeatIn = repeatIn.collect {
        case (k, v) if v > 1 => k -> (v - 1)
      }
      val rolesForReviveRepetition = (repeatIn.keySet -- newRepeatIn.keySet).filter {
        role => currentRoleState.get(role).contains(Unsuppressed)
      }

      repeatIn = newRepeatIn

      if (rolesForReviveRepetition.isEmpty) {
        Nil
      } else {
        List(IssueRevive(rolesForReviveRepetition))
      }
    }
  }

  case object Tick

  sealed trait RoleDirective

  /**
    *
    * @param roleState       The data specifying to which roles we should be subscribed, and which should be suppressed
    * @param newlyRevived    Convenience metadata - Set of roles that were previously non-existent or suppressed
    * @param newlySuppressed Convenience metadata - Set of roles that were previously not suppressed
    */
  case class UpdateFramework(
      roleState: Map[String, UpdateFramework.RoleState],
      newlyRevived: Set[String],
      newlySuppressed: Set[String]) extends RoleDirective

  object UpdateFramework {

    sealed trait RoleState

    case object Unsuppressed extends RoleState

    case object Suppressed extends RoleState

  }

  case class IssueRevive(roles: Set[String]) extends RoleDirective

}
