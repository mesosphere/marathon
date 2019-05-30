package mesosphere.marathon
package core.flow.impl

import akka.actor.{Actor, Props, Stash, Status}
import akka.event.{EventStream, LoggingReceive}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}

import scala.collection.immutable.HashSet

private[impl] class ReviveOffersActor(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    eventStream: EventStream,
    instanceTracker: InstanceTracker,
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with Stash with StrictLogging {

  private[this] val reviveCountMetric: Counter = metrics.counter("mesos.calls.revive")
  private[this] val suppressCountMetric: Counter = metrics.counter("mesos.calls.suppress")

  override def preStart(): Unit = {
    super.preStart()

    import akka.pattern.pipe
    import context.dispatcher
    instanceTracker.instancesBySpec().pipeTo(self)

    eventStream.subscribe(self, classOf[InstanceChanged])
  }

  override def postStop(): Unit = {
    eventStream.unsubscribe(self, classOf[InstanceChanged])
  }

  override def receive: Receive = initializing

  // initial state while loading instance state
  def initializing: Receive = LoggingReceive {
    case instances: InstanceTracker.InstancesBySpec =>
      val scheduledInstances: HashSet[Instance.Id] =
        instances.allInstances.withFilter(_.isScheduled).map(_.instanceId)(collection.breakOut)

      if (scheduledInstances.nonEmpty) {
        logger.info("revive offers: scheduled instances found during initialization")
        reviveOffers()
      } else {
        logger.info("suppress offers: no scheduled instances found during initialization")
        suppressOffers()
      }
      context.become(initialized(scheduledInstances))
      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading instances", cause)

    case _: AnyRef =>
      stash()
  }

  // state that reacts to instance changes
  def initialized(scheduledInstances: HashSet[Instance.Id]): Receive = LoggingReceive {
    // An instance is now Scheduled
    case update: InstanceChanged if update.condition == Condition.Scheduled =>
      if (scheduledInstances.contains(update.id)) {
        logger.debug(s"ignoring instance change for ${update.id} since it was already known to be Scheduled.")
      } else {
        val newState = scheduledInstances + update.id
        logger.info("revive offers: new Scheduled instance found")
        reviveOffers()
        context.become(initialized(newState))
      }

    // An instance is no longer Scheduled
    case update: InstanceChanged if update.condition != Condition.Scheduled =>
      if (scheduledInstances.contains(update.id)) {
        logger.debug(s"${update.id} is no longer scheduled; updating state")
        val newState = scheduledInstances - update.id
        if (newState.isEmpty) {
          logger.info("suppress offers: no scheduled instances left")
          suppressOffers()
        } else {
          logger.info(s"${newState.size} instances left; not suppressing offers")
        }
        context.become(initialized(newState))
      } else {
        logger.debug(s"ignoring instance change for ${update.id} since that instance not Scheduled.")
      }
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

private[flow] object ReviveOffersActor {
  def props(
    metrics: Metrics,
    conf: ReviveOffersConfig,
    marathonEventStream: EventStream,
    instanceTracker: InstanceTracker,
    driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(metrics, conf, marathonEventStream, instanceTracker, driverHolder))
  }
}
