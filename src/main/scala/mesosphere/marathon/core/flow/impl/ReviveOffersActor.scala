package mesosphere.marathon.core.flow.impl

import akka.actor.{ Cancellable, Actor, ActorLogging, Props }
import akka.event.{ EventStream, LoggingReceive }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.flow.impl.ReviveOffersActor.OffersWanted
import mesosphere.marathon.event.{ SchedulerReregisteredEvent, SchedulerRegisteredEvent }
import mesosphere.marathon.state.Timestamp
import rx.lang.scala.{ Observable, Subscription }
import scala.annotation.tailrec
import scala.concurrent.duration._

private[flow] object ReviveOffersActor {
  def props(
    clock: Clock, conf: ReviveOffersConfig,
    marathonEventStream: EventStream,
    offersWanted: Observable[Boolean], driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(clock, conf, marathonEventStream, offersWanted, driverHolder))
  }

  private[impl] case object TimedCheck
  private[impl] case class OffersWanted(wanted: Boolean)
}

/**
  * Revive offers whenever interest is signaled but maximally every 5 seconds.
  */
private[impl] class ReviveOffersActor(
    clock: Clock, conf: ReviveOffersConfig,
    marathonEventStream: EventStream,
    offersWanted: Observable[Boolean],
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with ActorLogging {

  private[impl] var subscription: Subscription = _
  private[impl] var offersCurrentlyWanted: Boolean = false
  private[impl] var revivesNeeded: Int = 0
  private[impl] var lastRevive: Timestamp = Timestamp(0)
  private[impl] var nextReviveCancellableOpt: Option[Cancellable] = None

  override def preStart(): Unit = {
    subscription = offersWanted.map(OffersWanted(_)).subscribe(self ! _)
    marathonEventStream.subscribe(self, classOf[SchedulerRegisteredEvent])
    marathonEventStream.subscribe(self, classOf[SchedulerReregisteredEvent])
  }

  override def postStop(): Unit = {
    subscription.unsubscribe()
    nextReviveCancellableOpt.foreach(_.cancel())
    nextReviveCancellableOpt = None
    marathonEventStream.unsubscribe(self)
  }

  @tailrec
  private[this] def reviveOffers(): Unit = {
    val now: Timestamp = clock.now()
    val nextRevive = lastRevive + conf.minReviveOffersInterval().milliseconds

    if (nextRevive <= now) {
      log.info("=> revive offers NOW, canceling any scheduled revives")
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None

      driverHolder.driver.foreach(_.reviveOffers())
      lastRevive = now

      revivesNeeded -= 1
      if (revivesNeeded > 0) {
        log.info("{} further revives still needed. Repeating reviveOffers according to --{} {}",
          revivesNeeded, conf.reviveOffersRepetitions.name, conf.reviveOffersRepetitions())
        reviveOffers()
      }
    }
    else {
      lazy val untilNextRevive = now until nextRevive
      if (nextReviveCancellableOpt.isEmpty) {
        log.info("=> Schedule next revive at {} in {}, adhering to --{} {} (ms)",
          nextRevive, untilNextRevive, conf.minReviveOffersInterval.name, conf.minReviveOffersInterval())
        nextReviveCancellableOpt = Some(schedulerCheck(untilNextRevive))
      }
      else if (log.isDebugEnabled) {
        log.info("=> Next revive already scheduled at {} not yet due for {}", nextRevive, untilNextRevive)
      }
    }
  }

  override def receive: Receive = LoggingReceive {
    Seq(
      receiveOffersWantedNotifications,
      receiveReviveOffersEvents
    ).reduceLeft[Receive](_.orElse[Any, Unit](_))
  }

  private[this] def receiveOffersWantedNotifications: Receive = {
    case OffersWanted(true) =>
      log.info("Received offers WANTED notification")
      offersCurrentlyWanted = true
      initiateNewSeriesOfRevives()

    case OffersWanted(false) =>
      log.info("Received offers NOT WANTED notification, canceling {} revives", revivesNeeded)
      offersCurrentlyWanted = false
      revivesNeeded = 0
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None
  }

  def initiateNewSeriesOfRevives(): Unit = {
    revivesNeeded = conf.reviveOffersRepetitions()
    reviveOffers()
  }

  private[this] def receiveReviveOffersEvents: Receive = {
    case msg @ (_: SchedulerRegisteredEvent | _: SchedulerReregisteredEvent | OfferReviverDelegate.ReviveOffers) =>

      if (offersCurrentlyWanted) {
        log.info(s"Received reviveOffers notification: ${msg.getClass.getSimpleName}")
        initiateNewSeriesOfRevives()
      }
      else {
        log.info(s"Ignoring ${msg.getClass.getSimpleName} because no one is currently interested in offers")
      }

    case ReviveOffersActor.TimedCheck =>
      log.info(s"Received TimedCheck")
      if (revivesNeeded > 0) {
        reviveOffers()
      }
      else {
        log.info("=> no revives needed right now")
      }
  }

  protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(duration, self, ReviveOffersActor.TimedCheck)
  }
}
