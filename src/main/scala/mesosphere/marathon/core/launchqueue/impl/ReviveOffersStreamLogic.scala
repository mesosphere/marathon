package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
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
    .via {
      TimedEmitter.flow
    }

  def suppressAndReviveStream(
    instanceUpdates: InstanceTracker.InstanceUpdates,
    delayedConfigRefs: Source[TimedEmitter.EventState[RunSpecConfigRef], NotUsed],
    reviveOffersRepetitions: Int, minReviveOffersInterval: FiniteDuration): Source[Op, NotUsed] = {

    val flattened = instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        Source.single[InstanceChangeOrSnapshot](snapshot).concat(updates.map { lol => logger.info(s"evt1 = ${lol}"); lol })
    }
    val zero = ReviveOffersState(InstancesSnapshot(Nil))
    flattened.map(Left(_)).merge(delayedConfigRefs.map(Right(_))).scan(zero) {
      case (current, Left(snapshot: InstancesSnapshot)) => ReviveOffersState(snapshot)
      case (current, Left(InstanceUpdated(updated, _, _))) => current.withInstanceUpdated(updated)
      case (current, Left(InstanceDeleted(deleted, _, _))) => current.withInstanceDeleted(deleted)
      case (current, Right(TimedEmitter.Active(configRef))) => current.withDelay(configRef)
      case (current, Right(TimedEmitter.Inactive(configRef))) => current.withoutDelay(configRef)
    }
      .via(reviveOrSuppress(reviveOffersRepetitions = reviveOffersRepetitions, minReviveOffersInterval = minReviveOffersInterval))
      .via(EnrichedFlow.debounce(minReviveOffersInterval))
  }

  /**
    * This flow emits a [[Suppress]] or a [[Revive]] based on the last two states.
    *
    * @param reviveOffersRepetitions Revive calls are repeated to a void a race condition with the offer matcher.
    * @param minReviveOffersInterval The interval between two revive calls.
    * @return The actual flow.
    */
  def reviveOrSuppress(reviveOffersRepetitions: Int, minReviveOffersInterval: FiniteDuration): Flow[ReviveOffersState, Op, NotUsed] = Flow[ReviveOffersState]
    .sliding(2)
    .mapConcat {
      case Seq(previous, current) =>
        val (diffScheduled, diffTerminal) = current -- previous
        if (diffScheduled.nonEmpty || diffTerminal.nonEmpty) {
          logger.info(s"Revive because new new scheduled $diffScheduled or terminal $diffTerminal.")
          Seq.fill(reviveOffersRepetitions)(Revive)
        } else if (current.isEmpty()) {
          logger.info(s"Suppress because both sets are empty.")
          List(Suppress)
        } else {
          logger.info("Nothing changed in last frame.")
          Nil
        }
      case _ =>
        logger.warn("Did not receive two elements; end of stream detected")
        Nil
    }
    .via(deduplicateSuppress)

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