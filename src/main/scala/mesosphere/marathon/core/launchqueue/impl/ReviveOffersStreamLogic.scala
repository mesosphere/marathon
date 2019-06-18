package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpecConfigRef
import mesosphere.marathon.stream.{EnrichedFlow, TimedEmitter}

import scala.concurrent.duration._

object ReviveOffersStreamLogic extends StrictLogging {

  /**
    * Watches a stream of rate limiter updates and emits Active(configRef) when a configRef has an active backoff delay,
    * and Inactive(configRef) when it doesn't any longer
    */
  val activelyDelayedRefs: Flow[RateLimiter.DelayUpdate, TimedEmitter.EventState[RunSpecConfigRef], NotUsed] = Flow[RateLimiter.DelayUpdate]
    .map { delayUpdate =>
      val deadline = delayUpdate.delay.map(_.deadline.toInstant)
      delayUpdate.ref -> deadline
    }
    .via { TimedEmitter.flow }

  def suppressAndReviveStream(
    instanceUpdates: InstanceTracker.InstanceUpdates,
    delayedConfigRefs: Source[TimedEmitter.EventState[RunSpecConfigRef], NotUsed],
    reviveOffersRepetitions: Int, minReviveOffersInterval: FiniteDuration): Source[Op, NotUsed] = {

    instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        val zero = ReviveOffersState(snapshot)
        updates.map(Left(_)).merge(delayedConfigRefs.map(Right(_))).scan(zero) {
          case (current, Left(InstanceUpdated(updated, _, _))) => current.withInstanceUpdated(updated)
          case (current, Left(InstanceDeleted(deleted, _, _))) => current.withInstanceDeleted(deleted)
          case (current, Right(TimedEmitter.Active(configRef))) => current.withDelay(configRef)
          case (current, Right(TimedEmitter.Inactive(configRef))) => current.withoutDelay(configRef)
        }
    }
      .via(EnrichedFlow.debounce[ReviveOffersState](3.seconds)) // Only process the latest element in 2 second.
      //.via(flowIgnoresDecline)
      .via(flowRespectsDecline(reviveOffersRepetitions = reviveOffersRepetitions, minReviveOffersInterval = minReviveOffersInterval)) // Still fails mesosphere.marathon.integration.AppDeployIntegrationTest
      .prepend(Source.single(Suppress))
  }

  /**
    * This flow revives solely based on the current state. It will revive on every tick as long as
    * there are scheduled instances. That means even if the scheduled instances declined all offers
    * these will come back.
    */
  def flowIgnoresDecline: Flow[ReviveOffersState, Op, NotUsed] = Flow[ReviveOffersState]
    .map(_.evaluate) // Decide whether to suppress or revive based on time and delay.
    .via(deduplicateSuppress)

  /**
    * This flow only revives if a run spec became scheduled or an resident task terminal. It will
    * also repeat Revive calls and respect declined offers, ie if we have scheduled instances but
    * they refused all offers we are not going to revive.
    */
  def flowRespectsDecline(reviveOffersRepetitions: Int, minReviveOffersInterval: FiniteDuration): Flow[ReviveOffersState, Op, NotUsed] = Flow[ReviveOffersState]
    .map(_.freeze) // Get all scheduled run specs based on delay and terminal instances.
    .sliding(2)
    .mapConcat {
      case Seq((oldScheduled, oldTerminal), (newScheduled, newTerminal)) =>
        val diffScheduled = newScheduled -- oldScheduled
        val diffTerminal = newTerminal -- oldTerminal
        if (diffScheduled.nonEmpty || diffTerminal.nonEmpty) {
          logger.info(s"Revive because new: $newScheduled and old $oldScheduled = diff $diffScheduled")
          Seq.fill(reviveOffersRepetitions)(Revive)
        } else if (newScheduled.isEmpty && newTerminal.isEmpty) {
          logger.info(s"Suppress because both sets are empty.")
          List(Suppress)
        } else {
          Nil
        }
    }
    .via(deduplicateSuppress)
    .throttle(1, minReviveOffersInterval)

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
}