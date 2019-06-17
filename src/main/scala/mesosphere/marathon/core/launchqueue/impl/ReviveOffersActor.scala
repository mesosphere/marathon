package mesosphere.marathon
package core.launchqueue.impl

import akka.{Done, NotUsed}
import akka.actor.{Actor, Props, Stash}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
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
  * @param scheduledRunSpecs All run specs that have at least one scheduled instance requiring offers.
  * @param terminalReservations Ids of terminal resident instance with [[Goal.Decommissioned]].
  * @param delays Delays for run specs.
  */
case class ReviveActorState(
    scheduledRunSpecs: Set[RunSpecConfigRef],
    terminalReservations: Set[Instance.Id],
    delays: Map[RunSpecConfigRef, RateLimiter.Delay]) extends StrictLogging {

  /** @return this state updated with an instance. */
  def withInstanceUpdated(instance: Instance): ReviveActorState = {
    logger.info(s"${instance.instanceId} updated to ${instance.state}")
    if (instance.isScheduled) copy(scheduledRunSpecs = scheduledRunSpecs + instance.runSpec.configRef)
    else if (ReviveActorState.shouldUnreserve(instance)) copy(terminalReservations = terminalReservations + instance.instanceId)
    else this
  }

  /** @return this state with passed instance removed from [[scheduledRunSpecs]] and [[terminalReservations]]. */
  def withInstanceDeleted(instance: Instance): ReviveActorState = {
    logger.info(s"${instance.instanceId} deleted.")
    copy(scheduledRunSpecs - instance.runSpec.configRef, terminalReservations - instance.instanceId)
  }

  /** @return this state with updated [[delays]]. */
  def withDelayUpdate(update: RateLimiter.DelayUpdate): ReviveActorState = update match {
    case RateLimiter.DelayUpdate(ref, Some(delay)) =>
      logger.info(s"Update delay for $ref to ${delay.deadline}")
      copy(delays = delays.updated(ref, delay))
    case RateLimiter.DelayUpdate(ref, None) =>
      logger.info(s"Remove delay for $ref")
      copy(delays = delays - ref)
  }

  def freeze(now: Timestamp): (Set[RunSpecConfigRef], Set[Instance.Id]) = {
    (scheduledRunSpecs.filter(launchAllowed(now)), terminalReservations)
  }

  // ------- ALTERNATIVE ------

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
    scheduledRunSpecs.exists(launchAllowed(now))
  }

  /** @return true if a instance has no active backoff. */
  def launchAllowed(now: Timestamp)(ref: RunSpecConfigRef): Boolean = {
    delays.get(ref).forall(_.deadline <= now)
  }
}

object ReviveActorState {
  def apply(snapshot: InstancesSnapshot): ReviveActorState = {
    ReviveActorState(
      snapshot.instances.view.filter(_.isScheduled).map(i => i.runSpec.configRef).toSet,
      snapshot.instances.view.filter(shouldUnreserve).map(_.instanceId).toSet,
      Map.empty
    )
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
      .via(EnrichedFlow.debounce[ReviveActorState](3.seconds)) // Only process the latest element in 2 second.
      //.via(flowIgnoresDecline)
      .via(flowRespectsDecline) // Still fails mesosphere.marathon.integration.AppDeployIntegrationTest
      .runWith(Sink.foreach {
        case Revive =>
          reviveCountMetric.increment()
          logger.info("Sending revive")
          driverHolder.driver.foreach(_.reviveOffers())
        case Suppress =>
          suppressCountMetric.increment()
          logger.info("Sending suppress")
          driverHolder.driver.foreach(_.suppressOffers())
      })
  }

  /**
    * This flow revives solely based on the current state. It will revive on every tick as long as
    * there are scheduled instances. That means even if the scheduled instances declined all offers
    * these will come back.
    */
  def flowIgnoresDecline: Flow[ReviveActorState, Op, NotUsed] = Flow[ReviveActorState]
    .map(_.evaluate(Timestamp.now())) // Decide whether to suppress or revive based on time and delay.
    .via(deduplicateSuppress)

  /**
    * This flow only revives if a run spec became scheduled or an resident task terminal. It will
    * also repeat Revive calls and respect declined offers, ie if we have scheduled instances but
    * they refused all offers we are not going to revive.
    */
  def flowRespectsDecline: Flow[ReviveActorState, Op, NotUsed] = Flow[ReviveActorState]
    .map(_.freeze(Timestamp.now())) // Get all scheduled run specs based on delay and terminal instances.
    .sliding(2)
    .mapConcat {
      case Seq((oldScheduled, oldTerminal), (newScheduled, newTerminal)) =>
        val diffScheduled = newScheduled -- oldScheduled
        val diffTerminal = newTerminal -- oldTerminal
        if (diffScheduled.nonEmpty || diffTerminal.nonEmpty) {
          logger.info(s"Revive because new: $newScheduled and old $oldScheduled = diff $diffScheduled")
          Seq.fill(conf.reviveOffersRepetitions())(Revive)
        } else if (newScheduled.isEmpty && newTerminal.isEmpty) {
          logger.info(s"Suppress because both sets are empty.")
          List(Suppress)
        } else {
          Nil
        }
    }
    .via(deduplicateSuppress)
    .throttle(1, conf.minReviveOffersInterval().millis)

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
