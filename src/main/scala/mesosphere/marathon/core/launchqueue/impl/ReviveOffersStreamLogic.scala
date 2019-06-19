package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.stream.{EnrichedFlow, Repeater, TimedEmitter}

import scala.concurrent.duration._

object ReviveOffersStreamLogic extends StrictLogging {
  sealed trait DelayedStatus
  case class Delayed(element: RunSpecConfigRef) extends DelayedStatus
  case class NotDelayed(element: RunSpecConfigRef) extends DelayedStatus

  /**
    * Watches a stream of rate limiter updates and emits Active(configRef) when a configRef has an active backoff delay,
    * and Inactive(configRef) when it doesn't any longer
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
    var lastElement: Op = null

    {
      case Suppress =>
        if (lastElement == Suppress) {
          Nil
        } else {
          lastElement = Suppress
          Seq(Suppress)
        }
      case Revive =>
        lastElement = Revive
        Seq(Revive)
    }
  })

  val reviveStateFromInstancesAndDelays: Flow[Either[InstanceChangeOrSnapshot, DelayedStatus], ReviveOffersState, NotUsed] = {
    val zero = ReviveOffersState(InstancesSnapshot(Nil))
    Flow[Either[InstanceChangeOrSnapshot, DelayedStatus]].scan(zero) {
      case (current, Left(snapshot: InstancesSnapshot)) => ReviveOffersState(snapshot)
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
  def shouldReconcileReservation(current: ReviveOffersState, previous: ReviveOffersState): Boolean =
    current.forceExpungedInstances > previous.forceExpungedInstances

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
        val diffTerminal = current.terminalReservations -- previous.terminalReservations

        if (diffScheduled.nonEmpty || diffTerminal.nonEmpty) {
          logger.info(s"Revive because new scheduled $diffScheduled or terminal $diffTerminal.")
          List.fill(3)(Revive)
        } else if (shouldReconcileReservation(current, previous)) {
          logger.info(s"Revive to trigger reservation reconciliation. Current ${current.forceExpungedInstances}, previous ${previous.forceExpungedInstances}")
          List(Revive)
        } else if (current.isEmpty) {
          logger.info(s"Suppress because there are no pending instances right now and current force expunged ${current.forceExpungedInstances} == ${previous.forceExpungedInstances}.")
          List(Suppress)
        } else {
          logger.info("Nothing changed in last frame.")
          Nil
        }
      case _ =>
        logger.warn("Did not receive two elements; end of stream detected")
        Nil
    }

  def suppressAndReviveStream(
    instanceUpdates: InstanceTracker.InstanceUpdates,
    delayedConfigRefs: Source[DelayedStatus, NotUsed],
    minReviveOffersInterval: FiniteDuration): Source[Op, NotUsed] = {

    val flattened = instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        Source.single[InstanceChangeOrSnapshot](snapshot).concat(updates)
    }
    flattened.map(Left(_)).merge(delayedConfigRefs.map(Right(_)))
      .via(reviveStateFromInstancesAndDelays)
      .via(EnrichedFlow.debounce(minReviveOffersInterval)) // Debounce must happen before diffing.
      .via(suppressOrReviveFromDiff)
      // There's a very small chance that we decline an offer in response to a revive for an instance not yet registered
      // with the TaskLauncherActor. To deal with the rare case this happens, we just repeat the last suppress / revive
      // after a while.
      //      .via(Repeater(minReviveOffersInterval, count = 1))
      .via(deduplicateSuppress)
      .throttle(1, minReviveOffersInterval)
  }
}