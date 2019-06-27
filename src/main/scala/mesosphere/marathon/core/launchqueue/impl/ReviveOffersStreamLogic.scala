package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
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

  /**
    * If two suppresses are sent in a row, filter them out
    */
  val deduplicateSuppress: Flow[Op, Op, NotUsed] = Flow[Op].statefulMapConcat(() => {
    var justSuppressed: Boolean = false

    {
      case Suppress =>
        if (justSuppressed) {
          Nil
        } else {
          justSuppressed = true
          List(Suppress)
        }
      case Revive =>
        justSuppressed = false
        List(Revive)
    }
  })

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
    * A resident instances was deleted and requires a revive.
    *
    * Currently Marathon uses [[InstanceTrackerDelegate.forceExpunge]] when a run spec with resident instances
    * is removed. Thus Marathon looses all knowledge of any reservations to these instances. The [[OfferMatcherReconciler]]
    * is supposed to filter offers for these reservations and destroy them if no related instance is known.
    *
    * A revive call to trigger an offer with said reservations to be destroyed should be emitted. There is no
    * guarantee that the reservation is destroyed.
    *
    * @param current The current accumulated state.
    * @param previous The accumulated state of the last check.
    * @return true if there is a new force expunged resident instance, false otherwise.
    */
  def shouldTryToReleaseForceExpungedResidentInstances(current: ReviveOffersState, previous: ReviveOffersState): Boolean =
    current.forceExpungedResidentInstances > previous.forceExpungedResidentInstances

  /**
    * This flow emits a [[Suppress]] or a [[Revive]] based on the last two states emitted by [[reviveStateFromInstancesAndDelays]]
    *
    * @return The actual flow.
    */
  val suppressOrReviveFromDiff: Flow[ReviveOffersState, Op, NotUsed] = Flow[ReviveOffersState]
    .sliding(2)
    .mapConcat {
      case Seq(previous, current) =>

        val diffScheduled = current.scheduledInstancesWithoutBackoff -- previous.scheduledInstancesWithoutBackoff
        def diffTerminal = current.terminalReservations -- previous.terminalReservations

        if (diffScheduled.nonEmpty) {
          logger.info(s"Issuing revive as there are newly scheduled instances to launch $diffScheduled")
          // There's a very small chance that we decline an offer in response to a revive for an instance not yet registered
          // with the TaskLauncherActor. To deal with the rare case this happens, we just repeat the revive call a few times
          List(Revive, Revive, Revive)
        } else if (diffTerminal.nonEmpty) {
          logger.info(s"Issuing revive as there are decommissioned resident instances that need their reservations cleaned up: $diffTerminal")
          List(Revive)
        } else if (shouldTryToReleaseForceExpungedResidentInstances(current, previous)) {
          logger.info(s"Revive to trigger reservation reconciliation.")
          List(Revive)
        } else if (current.isEmpty) {
          logger.info(s"Suppress because there are no pending instances right now.")
          List(Suppress)
        } else {
          Nil
        }
      case _ =>
        logger.warn("End of stream detected in suppress/revive logic.")
        Nil
    }

  def handleIgnoreSuppress(enableSuppress: Boolean): Flow[Op, Op, NotUsed] = {
    if (enableSuppress)
      Flow[Op] // do nothing
    else
      Flow[Op].filter { _ != Suppress }
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
    enableSuppress: Boolean): Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], Op, NotUsed] = {

    reviveStateFromInstancesAndDelays
      .buffer(1, OverflowStrategy.dropHead) // While we are back-pressured, we drop older interim frames
      .via(suppressOrReviveFromDiff)
      .via(handleIgnoreSuppress(enableSuppress = enableSuppress))
      .via(deduplicateSuppress)
      .throttle(1, minReviveOffersInterval)
  }
}
