package mesosphere.marathon.tasks

import akka.actor.{ Props, Cancellable, ActorLogging, Actor }
import akka.event.{ EventStream, LoggingReceive }
import mesosphere.marathon.event.{ SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.state.Timestamp
import scala.concurrent.duration._

object OfferReviverActor {
  final val NAME = "offerReviver"

  def props(conf: OfferReviverConf,
            eventStream: EventStream,
            driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new OfferReviverActor(conf, eventStream, driverHolder))
  }
}

/**
  * Revive offers whenever interest is signaled but maximally every 5 seconds.
  */
private class OfferReviverActor(
    conf: OfferReviverConf,
    eventStream: EventStream,
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with ActorLogging {
  private[this] var lastRevive: Timestamp = Timestamp(0)
  private[this] var nextReviveCancellableOpt: Option[Cancellable] = None

  override def preStart(): Unit = {
    log.info("Starting up")

    // We want to revive offers when the scheduler re-registers
    // with the Mesos master.
    eventStream.subscribe(self, classOf[SchedulerReregisteredEvent])

    // For some reason, when the scheduler re-registers with an
    // existing framework ID, we have observed the `registered`
    // callback instead.
    eventStream.subscribe(self, classOf[SchedulerRegisteredEvent])
  }

  override def postStop(): Unit = {
    log.info("Stopping")

    nextReviveCancellableOpt.foreach(_.cancel())
    nextReviveCancellableOpt = None

    eventStream.unsubscribe(self)
  }

  private[this] def reviveOffers(): Unit = {
    val now: Timestamp = Timestamp.now()
    val nextRevive: Timestamp = lastRevive + conf.minReviveOffersInterval().milliseconds

    if (nextRevive <= now) {
      log.info("=> revive offers NOW, canceling any scheduled revives")
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None

      driverHolder.driver.foreach(_.reviveOffers())
      lastRevive = now
    }
    else {
      lazy val untilNextRevive = now until nextRevive
      if (nextReviveCancellableOpt.isEmpty) {
        log.info("=> Schedule next revive at {} in {}, adhering to --{} {} (ms)",
          nextRevive, untilNextRevive, conf.minReviveOffersInterval.name, conf.minReviveOffersInterval())
        nextReviveCancellableOpt = Some(scheduleCheck(untilNextRevive))
      }
      else {
        log.info("=> Next revive already scheduled at {} not yet due for {}", nextRevive, untilNextRevive)
      }
    }
  }

  override def receive: Receive = LoggingReceive {
    case OfferReviverDelegate.ReviveOffers =>
      log.info("Received request to revive offers")
      reviveOffers()

    case _: SchedulerReregisteredEvent =>
      log.info("Received scheduler reregistration event")
      reviveOffers()

    case _: SchedulerRegisteredEvent =>
      log.info("Received scheduler registration event")
      reviveOffers()
  }

  protected def scheduleCheck(duration: FiniteDuration): Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(duration, self, OfferReviverDelegate.ReviveOffers)
  }
}
