package mesosphere.marathon
package core.launchqueue.impl

import akka.{Done, NotUsed}
import akka.actor.{Actor, Props, Stash}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.ReviveOffersConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}
import mesosphere.marathon.state.{RunSpecConfigRef, Timestamp}
import mesosphere.marathon.stream.EnrichedFlow

import scala.concurrent.duration._

sealed trait Op
case object Revive extends Op
case object Suppress extends Op

/**
  * Holds the current state and defines the revive logic.
  *
  * @param scheduledInstances All instances that are scheduled an require offers.
  * @param terminalReservations Ids of terminal resident instance with [[Goal.Decommissioned]].
  * @param delays Delays for run specs.
  */
case class ReviveActorState(
    scheduledInstances: Map[Instance.Id, Instance],
    terminalReservations: Set[Instance.Id],
    delays: Map[RunSpecConfigRef, RateLimiter.Delay]) extends StrictLogging {

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveActorState = {
    logger.info(s"${instance.instanceId} updated.")
    if (instance.isScheduled) copy(scheduledInstances = scheduledInstances.updated(instance.instanceId, instance))
    else if (ReviveActorState.shouldUnreserve(instance)) copy(terminalReservations = terminalReservations + instance.instanceId)
    else this
  }

  /** @return this state with passed instance removed from [[scheduledInstances]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveActorState = {
    logger.info(s"${instance.instanceId} deleted.")
    copy(scheduledInstances - instance.instanceId, terminalReservations - instance.instanceId)
  }

  /** @return this state with updated [[delays]]. */
  def withDelayUpdate(update: RateLimiter.DelayUpdate): ReviveActorState = update match {
    case RateLimiter.DelayUpdate(ref, Some(delay)) => copy(delays = delays.updated(ref, delay))
    case RateLimiter.DelayUpdate(ref, None) => copy(delays = delays - ref)
  }

  /**
    * Evaluate whether we should revive or suppress based on the current state ''and'' time.
    *
    * Since methods is time based it is ''not'' idempotent given the same state but different timestamp.
    * This is important when we evaluate the state a second time triggered by a tick. Scheduled instances
    * might suddenly not have an active backoff and thus a revive call is triggered.
    *
    * @param now The current time.
    * @return whether we should revive or suppress.
    */
  def evaluate(now: Timestamp): Op = {
    if (terminalReservations.nonEmpty || scheduledInstanceWithoutBackoffExists(now)) {
      logger.info("Reviving offers.")
      Revive
    } else {
      logger.info("Suppressing offers.")
      Suppress
    }
  }

  /** @return true if there is at least one scheduled instance that has no active backoff. */
  def scheduledInstanceWithoutBackoffExists(now: Timestamp): Boolean = {
    scheduledInstances.values.exists(launchAllowed(now))
  }

  /** @return true if a instance has no active backoff. */
  def launchAllowed(now: Timestamp)(instance: Instance): Boolean = {
    delays.get(instance.runSpec.configRef).forall(_.deadline <= now)
  }
}

object ReviveActorState {
  def apply(snapshot: InstancesSnapshot): ReviveActorState = {
    ReviveActorState(snapshot.instances.filter(_.isScheduled).map(i => i.instanceId -> i).toMap, snapshot.instances.view.filter(shouldUnreserve).map(_.instanceId).toSet, Map.empty)
  }

  /** @return whether the instance has a reservation that can be freed. */
  def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }
}

class ReviveOffersActor(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with Stash with StrictLogging {

  private[this] val reviveCountMetric: Counter = metrics.counter("mesos.calls.revive")
  private[this] val suppressCountMetric: Counter = metrics.counter("mesos.calls.suppress")

  implicit val mat = ActorMaterializer()

  override def preStart(): Unit = {
    super.preStart()

    instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        val zero = ReviveActorState(snapshot)
        updates.merge(rateLimiterUpdates).merge(Source.tick(1.seconds, 1.seconds, 'tick)).scan(zero) {
          case (current, InstanceUpdated(updated, _, _)) => current.withInstanceUpdated(updated)
          case (current, InstanceDeleted(deleted, _, _)) => current.withInstanceDeleted(deleted)
          case (current, delayUpdate: RateLimiter.DelayUpdate) => current.withDelayUpdate(delayUpdate)
          case (current, 'tick) => current // Retrigger evaluation of delays.
        }
    }
      .map(_.evaluate(Timestamp.now()))
      .via(EnrichedFlow.debounce[Op](2.seconds)) // Only process the latest op in 2 second.
      // TODO: emit last element if now new element was received in the last X seconds.
      .runWith(Sink.actorRef[Op](self, Done)) // TODO: replace actor sink with Sinke.foreach.
  }

  override def receive: Receive = LoggingReceive {
    case Revive => reviveOffers()
    case Suppress => suppressOffers()
    case Done => context.stop(self)
    case other =>
      logger.info(s"Unexpected message $other")
  }

  def reviveOffers(): Unit = {
    reviveCountMetric.increment()
    logger.info("Sending revive")
    driverHolder.driver.foreach(_.reviveOffers())
  }

  def suppressOffers(): Unit = {
    suppressCountMetric.increment()
    logger.info("Sending suppress")
    driverHolder.driver.foreach(_.suppressOffers())
  }

  /** @return whether the instance has a reservation that can be freed. */
  def shouldUnreserve(instance: Instance): Boolean = {
    instance.reservation.nonEmpty && instance.state.goal == Goal.Decommissioned && instance.state.condition.isTerminal
  }

  /** @return whether a launch backoff is active for a scheduled instance. */
  def backoffIsActive(instance: Instance, update: RateLimiter.DelayUpdate): Boolean = {
    instance.isScheduled && (instance.runSpec.configRef == update.ref) && update.delay.exists(_.deadline.after(Timestamp.now()))
  }
}

object ReviveOffersActor {
  def props(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(metrics, conf, instanceUpdates, rateLimiterUpdates, driverHolder))
  }
}
