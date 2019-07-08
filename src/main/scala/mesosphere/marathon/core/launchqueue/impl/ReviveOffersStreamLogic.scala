package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.UpdateFramework.Unsuppressed
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.stream.TimedEmitter

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
      case (current, Left(InstanceUpdated(updated, _, _))) => current.withInstanceUpdated(updated)
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
    * @param enableSuppress - Whether or not to enable offer suppression
    * @return
    */
  def suppressAndReviveFlow(
    minReviveOffersInterval: FiniteDuration,
    enableSuppress: Boolean): Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], RoleDirective, NotUsed] = {

    val reviveRepeaterWithTicks = Flow[RoleDirective]
      .map(Left(_))
      .merge(Source.tick(minReviveOffersInterval, minReviveOffersInterval, Right(Tick)))
      .via(reviveRepeater)

    reviveStateFromInstancesAndDelays
      .buffer(1, OverflowStrategy.dropHead) // While we are back-pressured, we drop older interim frames
      .throttle(1, minReviveOffersInterval)
      .map(_.roleReviveVersions)
      .via(reviveDirectiveFlow(enableSuppress))
      .via(reviveRepeaterWithTicks)
  }

  def reviveDirectiveFlow(enableSuppress: Boolean): Flow[Map[String, RoleOfferState], RoleDirective, NotUsed] = Flow[Map[String, RoleOfferState]].statefulMapConcat({ () =>

    var lastState: Map[String, RoleOfferState] = Map.empty
    def lastReviveVersion(role: String): Option[Long] =
      lastState.get(role).collect { case OffersWanted(version) => version }

    def suppressedRoles(state: Map[String, RoleOfferState]): Set[String] =
      state.collect { case (role, OffersNotWanted) => role }.toSet

    {
      case newState =>

        val rolesChanged = lastState.keySet != newState.keySet
        val suppressedChanged = suppressedRoles(lastState) != suppressedRoles(newState)
        var directives = List.newBuilder[RoleDirective]

        if (enableSuppress) {
          if (rolesChanged || suppressedChanged) {
            val updateFramework = UpdateFramework(newState.map {
              case (role, OffersWanted(_)) => role -> UpdateFramework.Unsuppressed
              case (role, OffersNotWanted) => role -> UpdateFramework.Suppressed
            })
            directives += updateFramework
          } else {
            val updateFramework = UpdateFramework(newState.keysIterator.map { role =>
              role -> UpdateFramework.Unsuppressed
            }.toMap)
            val newlyRevived = newState.iterator
              .collect { case (role, OffersWanted(_)) if !lastState.get(role).exists(_.isWanted) => role }
              .toSet
            directives += updateFramework
            directives += IssueRevive(newlyRevived)
          }
        }

        val rolesNeedingRevive = newState.view.collect { case (role, OffersWanted(version)) if lastReviveVersion(role).exists(_ < version) => role }.toSet
        if (rolesNeedingRevive.nonEmpty)
          directives += IssueRevive(rolesNeedingRevive)

        directives.result()
    }
  })

  def reviveRepeater: Flow[Either[RoleDirective, Tick.type], RoleDirective, NotUsed] = Flow[Either[RoleDirective, Tick.type]]
    .statefulMapConcat { () =>

      var lastRoleState: Map[String, UpdateFramework.RoleState] = Map.empty
      var repeatIn: Map[String, Int] = Map.empty

      def markRolesForRepeat(roles: Iterable[String]): Unit =
        roles.foreach { role =>
          repeatIn += role -> 2
        }

      {
        case Left(msg @ UpdateFramework(roleState)) =>
          val newlyUnsuppressedRoles = roleState.collect { case (role, UpdateFramework.Unsuppressed) if lastRoleState.get(role).contains(UpdateFramework.Suppressed) => role }.toSeq
          lastRoleState = roleState

          markRolesForRepeat(newlyUnsuppressedRoles)

          List(msg)

        case Left(r @ IssueRevive(roles)) =>
          markRolesForRepeat(roles) // set / reset the repeat delay
          List(r)

        case Right(Tick) =>
          val newRepeatIn = repeatIn.collect { case (k, v) if v > 0 => k -> (v - 1) }
          val rolesForReviveRepetition = (repeatIn.keySet -- newRepeatIn.keySet).filter { role => lastRoleState.get(role).contains(Unsuppressed) }

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
  case class UpdateFramework(roleState: Map[String, UpdateFramework.RoleState]) extends RoleDirective
  object UpdateFramework {
    sealed trait RoleState
    case object Unsuppressed extends RoleState
    case object Suppressed extends RoleState
  }
  case class IssueRevive(roles: Set[String]) extends RoleDirective
}
