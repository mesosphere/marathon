package mesosphere.marathon
package core.flow.impl

import akka.actor.{ Actor, Cancellable, Props }
import akka.event.{ EventStream, LoggingReceive }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.flow.impl.ReviveOffersActor.OffersWanted
import mesosphere.marathon.core.event.{ SchedulerRegisteredEvent, SchedulerReregisteredEvent }
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
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with StrictLogging {

  private[impl] var subscription: Subscription = _
  private[impl] var offersCurrentlyWanted: Boolean = false
  private[impl] var revivesNeeded: Int = 0
  private[impl] var lastRevive: Timestamp = Timestamp(0)
  private[impl] var nextReviveCancellableOpt: Option[Cancellable] = None

  override def preStart(): Unit = {
    subscription = offersWanted.map(OffersWanted).subscribe(self ! _)
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
      logger.info("=> revive offers NOW, canceling any scheduled revives")
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None

      driverHolder.driver.foreach(_.reviveOffers())
      lastRevive = now

      revivesNeeded -= 1
      if (revivesNeeded > 0) {
        logger.info(s"$revivesNeeded further revives still needed. Repeating reviveOffers according to --${conf.reviveOffersRepetitions.name} ${conf.reviveOffersRepetitions()}")
        reviveOffers()
      }
    } else {
      lazy val untilNextRevive = now until nextRevive
      if (nextReviveCancellableOpt.isEmpty) {
        logger.info(s"=> Schedule next revive at $nextRevive in $untilNextRevive, adhering to --${conf.minReviveOffersInterval.name} ${conf.minReviveOffersInterval()} (ms)")
        nextReviveCancellableOpt = Some(schedulerCheck(untilNextRevive))
      } else {
        logger.info("=> Next revive already scheduled at {} not yet due for {}", nextRevive, untilNextRevive)
      }
    }
  }

  private[this] def suppressOffers(): Unit = {
    logger.info("=> Suppress offers NOW")
    driverHolder.driver.foreach(_.suppressOffers())
  }

  override def receive: Receive = LoggingReceive {
    Seq(
      receiveOffersWantedNotifications,
      receiveReviveOffersEvents
    ).reduceLeft[Receive](_.orElse[Any, Unit](_))
  }

  private[this] def receiveOffersWantedNotifications: Receive = {
    case OffersWanted(true) =>
      logger.info("Received offers WANTED notification")
      offersCurrentlyWanted = true
      initiateNewSeriesOfRevives()

    case OffersWanted(false) =>
      logger.info("Received offers NOT WANTED notification, canceling {} revives", revivesNeeded)
      offersCurrentlyWanted = false
      revivesNeeded = 0
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None

      // When we don't want any more offers, we ask mesos to suppress
      // them. This alleviates load on the allocator, and acts as an
      // infinite duration filter for all agents until the next time
      // we call `Revive`.
      suppressOffers()
  }

  def initiateNewSeriesOfRevives(): Unit = {
    revivesNeeded = conf.reviveOffersRepetitions()
    reviveOffers()
  }

  private[this] def receiveReviveOffersEvents: Receive = {
    case msg @ (_: SchedulerRegisteredEvent | _: SchedulerReregisteredEvent | OfferReviverDelegate.ReviveOffers) =>

      if (offersCurrentlyWanted) {
        logger.info(s"Received reviveOffers notification: ${msg.getClass.getSimpleName}")
        initiateNewSeriesOfRevives()
      } else {
        logger.info(s"Ignoring ${msg.getClass.getSimpleName} because no one is currently interested in offers")
      }

    case ReviveOffersActor.TimedCheck =>
      logger.info("Received TimedCheck")
      if (revivesNeeded > 0) {
        reviveOffers()
      } else {
        logger.info("=> no revives needed right now")
      }
  }

  protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(duration, self, ReviveOffersActor.TimedCheck)
  }
}
