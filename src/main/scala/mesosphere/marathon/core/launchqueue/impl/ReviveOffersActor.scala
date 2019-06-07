package mesosphere.marathon
package core.launchqueue.impl

import akka.Done
import akka.actor.{Actor, Props, Stash}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.ReviveOffersConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}

sealed trait Ops
case object Revive extends Ops
case object Suppress extends Ops
case object Noop extends Ops

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
        // TODO: consider terminal resident instances that should be decommissioned.
        val zero: Set[Instance.Id] = snapshot.instances.filter(_.isScheduled).map(_.instanceId).toSet
        updates.scan(zero) {
          case (current, update) =>
            if (update.instance.isScheduled) current + update.instance.instanceId
            else current - update.instance.instanceId
        }
    }.sliding(2)
      .map{
        // TODO: consider backoff.
        case Seq(oldScheduledInstances: Set[Instance.Id], newScheduledInstances: Set[Instance.Id]) =>
          if ((newScheduledInstances &~ oldScheduledInstances).nonEmpty) {
            // We have new scheduled instances
            Revive
            logger.info("Reviving offers.")
          } else if (newScheduledInstances.isEmpty) {
            Suppress
            logger.info("Suppressing offers.")
          } else {
            logger.info("No action on instance update.")
            Noop // TODO: should we keep reviving?
          }
      }.runWith(Sink.actorRef(self, Done))
  }

  override def receive: Receive = LoggingReceive {
    case Revive => reviveOffers()
    case Suppress => suppressOffers()
    case Noop => // TODO: keep doing what we've done before.
    case Done => context.stop(self)
  }

  def reviveOffers(): Unit = {
    reviveCountMetric.increment()
    driverHolder.driver.foreach(_.reviveOffers())
  }

  def suppressOffers(): Unit = {
    suppressCountMetric.increment()
    driverHolder.driver.foreach(_.suppressOffers())
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
