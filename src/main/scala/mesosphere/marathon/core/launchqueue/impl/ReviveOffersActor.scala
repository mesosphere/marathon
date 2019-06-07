package mesosphere.marathon
package core.launchqueue.impl

import akka.Done
import akka.actor.{Actor, Props, Stash}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.ReviveOffersConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}
import mesosphere.marathon.stream.EnrichedFlow

sealed trait Op
case object Revive extends Op
case object Suppress extends Op
case object Noop extends Op

class ReviveOffersActor(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with Stash with StrictLogging {

  private[this] val reviveCountMetric: Counter = metrics.counter("mesos.calls.revive")
  private[this] val suppressCountMetric: Counter = metrics.counter("mesos.calls.suppress")

  implicit val mat = ActorMaterializer()

  override def preStart(): Unit = {
    super.preStart()

    instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        val zero: Set[Instance.Id] = snapshot.instances.view.filter { instance =>
          instance.isScheduled || shouldUnreserve(instance)
        }.map(_.instanceId).toSet
        updates.scan(zero) {
          case (current, update) =>
            logger.info(s"Processing update $update.")
            if (update.instance.isScheduled || shouldUnreserve(update.instance)) current + update.instance.instanceId
            else current - update.instance.instanceId
        }
    }.sliding(2)
      .map{
        // TODO: consider backoff.
        case Seq(oldInstances: Set[Instance.Id], newInstances: Set[Instance.Id]) =>
          logger.info(s"Considering old $oldInstances and new $newInstances")
          val addedInstances = newInstances &~ oldInstances
          if (addedInstances.nonEmpty) {
            logger.info(s"Reviving offers because of $addedInstances")

            // We have new scheduled instances
            Revive
          } else if (newInstances.isEmpty) {
            logger.info("Suppressing offers.")
            Suppress
          } else {
            logger.info("No action on instance update.")
            Noop // TODO: should we keep reviving?
          }
      }
      .filter(_ != Noop)
      .via(EnrichedFlow.dedup())
      .runWith(Sink.actorRef[Op](self, Done))
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
}

object ReviveOffersActor {
  def props(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(metrics, conf, instanceUpdates, driverHolder))
  }
}
