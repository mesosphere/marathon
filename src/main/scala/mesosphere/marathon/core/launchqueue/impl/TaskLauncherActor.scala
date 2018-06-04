package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock

import akka.Done
import akka.actor._
import akka.pattern._
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted}
import mesosphere.marathon.core.launcher.{InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.matcher.base.util.{InstanceOpSourceDelegate, OfferMatcherDelegate}
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, Region, RunSpec, Timestamp}
import org.apache.mesos.{Protos => Mesos}

import scala.async.Async.{async, await}
import scala.concurrent.{Future, Promise}

object TaskLauncherActor {
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskOpFactory: InstanceOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatisticsActor: ActorRef,
    localRegion: () => Option[Region])(
    runSpec: RunSpec): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      instanceTracker, rateLimiterActor, offerMatchStatisticsActor,
      runSpec, localRegion))
  }

  sealed trait Requests

  case class Sync(runSpec: RunSpec, instances: Seq[Instance]) extends Requests

  case class MatchOffer(offer: Mesos.Offer, promise: Promise[OfferMatchResult], instances: Seq[Instance]) extends Requests

  /**
    * Get the current count.
    * The actor responds with a [[QueuedInstanceInfo]] message.
    */
  case object GetCount extends Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  case object Stop extends Requests

  private val OfferOperationRejectedTimeoutReason: String =
    "InstanceLauncherActor: no accept received within timeout. " +
      "You can reconfigure the timeout with --task_operation_notification_timeout."

  /**
    * This is an alternative implementation to the [[OfferMatcherDelegate]]. We enrich the match request with all
    * instances of the run spec we want to launch.
    *
    * @param taskLauncherActor
    * @param runSpec
    * @param instanceTracker
    */
  class OfferMatcher(taskLauncherActor: ActorRef, runSpec: RunSpec, instanceTracker: InstanceTracker) extends base.OfferMatcher with StrictLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    // TODO(karsten): Once the delay is gone we can get rid of the task launcher actor and implement all matching in this method.
    override def matchOffer(offer: Mesos.Offer): Future[MatchedInstanceOps] = async {

      val instances = await(instanceTracker.list(runSpec.id))

      val p = Promise[OfferMatchResult]()
      taskLauncherActor ! MatchOffer(offer, p, instances)
      await(p.future) match {
        case matched: OfferMatchResult.Match =>
          logger.info(s"Matched offer ${offer.getId} for run spec ${runSpec.id}, ${runSpec.version}.")
          await(instanceTracker.process(matched.instanceOp.stateOp))
          logger.info("Saved update")
          MatchedInstanceOps(offer.getId, Seq(InstanceOpWithSource(InstanceOpSourceDelegate(taskLauncherActor), matched.instanceOp)))
        case notMatched: OfferMatchResult.NoMatch =>
          logger.info(s"Did not match offer ${offer.getId} for run spec ${runSpec.id}, ${runSpec.version}.")
          MatchedInstanceOps.noMatch(offer.getId)
      }
    }

    //set the precedence only, if this app is resident
    override def precedenceFor: Option[PathId] = if (runSpec.isResident) Some(runSpec.id) else None
  }
}

/**
  * Allows processing offers for starting tasks for the given app.
  */
private class TaskLauncherActor(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    instanceOpFactory: InstanceOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatisticsActor: ActorRef,

    private[this] var runSpec: RunSpec,
    localRegion: () => Option[Region]) extends Actor with StrictLogging with Stash {
  // scalastyle:on parameter.number

  /** instances that are in the tracker */
  // TODO(karsten): remove
  private[this] var instanceMap: Map[Instance.Id, Instance] = _

  private[this] def inFlightInstanceOperations(instances: Iterable[Instance]) = instances.filter(_.isProvisioned)

  def scheduledInstances(instances: Iterable[Instance]): Iterable[Instance] = instances.filter(_.isScheduled)

  // TODO(karsten): This number is not correct. We might want to launch instances on reservations as well.
  def instancesToLaunch(instances: Iterable[Instance]) = instances.count(_.isScheduled)
  def reservedInstances(instances: Iterable[Instance]): Iterable[Instance] = instances.filter(_.isReserved)

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  private[this] val startedAt = clock.now()

  override def preStart(): Unit = {
    super.preStart()

    import scala.concurrent.ExecutionContext.Implicits.global

    instanceTracker.list(runSpec.id).pipeTo(self)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightInstanceOperations(instanceMap.values).nonEmpty) {
      logger.warn(s"Actor shutdown while instances are in flight: ${inFlightInstanceOperations(instanceMap.values).map(_.instanceId).mkString(", ")}")
    }

    offerMatchStatisticsActor ! OfferMatchStatisticsActor.LaunchFinished(runSpec.id)

    super.postStop()

    logger.info(s"Stopped InstanceLauncherActor for ${runSpec.id} version ${runSpec.version}")
  }

  override def receive: Receive = initializing

  def initializing: Receive = {
    case instances: Seq[Instance] =>
      syncInstances(instances)

      logger.info(s"Started instanceLaunchActor for ${runSpec.id} version ${runSpec.version} with initial count ${instancesToLaunch(instanceMap.values)}")
      rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)

      unstashAll()
      context.become(waitForInitialDelay)
    case message: Any => stash()
  }

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(spec, delayUntil) if spec == runSpec =>
      logger.info(s"Got delay update for run spec ${spec.id}")
      stash()
      unstashAll()
      context.become(active)
    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      logger.warn(s"Received delay update for other run spec ${spec.id} version ${spec.version} and delay $delayUntil. Current run spec is ${runSpec.id} version ${runSpec.version}")
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveSync,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveInstanceUpdate,
      receiveGetCurrentCount,
      receiveProcessOffers,
      receiveUnknown
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def receiveUnknown: Receive = {
    case msg: Any =>
      // fail fast and do not let the sender time out
      sender() ! Status.Failure(new IllegalStateException(s"Unhandled message: $msg"))
  }

  private[this] def receiveStop: Receive = {
    case TaskLauncherActor.Stop =>
      if (inFlightInstanceOperations(instanceMap.values).nonEmpty) {
        val taskIds = inFlightInstanceOperations(instanceMap.values).take(3).map(_.instanceId).mkString(", ")
        logger.info(
          s"Still waiting for ${inFlightInstanceOperations(instanceMap.values).size} inflight messages but stopping anyway. " +
            s"First three task ids: $taskIds"
        )
      }
      context.stop(self)
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiterActor.DelayUpdate(spec, delayUntil) if spec == runSpec =>

      if (!backOffUntil.contains(delayUntil)) {

        backOffUntil = Some(delayUntil)

        recheckBackOff.foreach(_.cancel())
        recheckBackOff = None

        val now: Timestamp = clock.now()
        if (backOffUntil.exists(_ > now)) {
          import context.dispatcher
          recheckBackOff = Some(
            context.system.scheduler.scheduleOnce(now until delayUntil, self, RecheckIfBackOffUntilReached)
          )
        }

        OfferMatcherRegistration.manageOfferMatcherStatus()
      }

      logger.debug(s"After delay update ${status(instanceMap.values)}")

    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      logger.warn(s"Received delay update for other run spec ${spec.id} version ${spec.version} and delay $delayUntil. Current run spec is ${runSpec.id} version ${runSpec.version}")

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) =>
      logger.debug(s"Task op '${op.getClass.getSimpleName}' for ${op.instanceId} was REJECTED, reason '$reason', rescheduling. ${status(instanceMap.values)}")
      syncInstances()
      OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case update: InstanceDeleted =>
      logger.info(s"receiveInstanceUpdate: ${update.id} was deleted (${update.condition})")
      // A) If the app has constraints, we need to reconsider offers that
      // we already rejected. E.g. when a host:unique constraint prevented
      // us to launch tasks on a particular node before, we need to reconsider offers
      // of that node after a task on that node has died.
      //
      // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
      if (runSpec.constraints.nonEmpty || (runSpec.isResident && shouldLaunchInstances(instanceMap.values))) {
        maybeOfferReviver.foreach(_.reviveOffers())
      }
      syncInstances()
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done

    case change: InstanceChange =>
      syncInstances()
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done

  }

  private[this] def receiveGetCurrentCount: Receive = {
    case TaskLauncherActor.GetCount =>
      replyWithQueuedInstanceCount()
  }

  /**
    * Update internal instance map.
    */
  private[this] def receiveSync: Receive = {
    case TaskLauncherActor.Sync(newRunSpec, instances) =>
      val configChange = runSpec.isUpgrade(newRunSpec)
      if (configChange || runSpec.needsRestart(newRunSpec)) {
        logger.info(s"Received new run spec for ${newRunSpec.id} old version ${runSpec.version} to new version ${newRunSpec.version}")

        runSpec = newRunSpec // Side effect for suspendMatchingUntilWeGetBackoffDelayUpdate

        if (configChange) {
          suspendMatchingUntilWeGetBackoffDelayUpdate()
        }
      }
      syncInstances(instances)

      OfferMatcherRegistration.manageOfferMatcherStatus()
      replyWithQueuedInstanceCount()
  }

  private[this] def suspendMatchingUntilWeGetBackoffDelayUpdate(): Unit = {
    // signal no interest in new offers until we get the back off delay.
    // this makes sure that we see unused offers again that we rejected for the old configuration.
    OfferMatcherRegistration.unregister()

    // get new back off delay, don't do anything until we get that.
    backOffUntil = None
    rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)
    context.become(waitForInitialDelay)
  }

  private[this] def replyWithQueuedInstanceCount(): Unit = {
    val instances = instanceMap.values
    val activeInstances = instances.count(instance => instance.isActive || instance.isReserved)
    val instanceLaunchesInFlight = inFlightInstanceOperations(instances).size
    sender() ! QueuedInstanceInfo(
      runSpec,
      inProgress = scheduledInstances(instances).nonEmpty || reservedInstances(instances).nonEmpty || inFlightInstanceOperations(instances).nonEmpty,
      instancesLeftToLaunch = instancesToLaunch(instances),
      finalInstanceCount = instancesToLaunch(instances) + instanceLaunchesInFlight + activeInstances,
      backOffUntil.getOrElse(clock.now()),
      startedAt
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case OfferMatcherDelegate.MatchOffer(_, promise) =>
      promise.tryFailure(new UnsupportedOperationException("The task launcher actor only supports TaskLauncherActor.MatchOffer messages."))

    case TaskLauncherActor.MatchOffer(offer, promise, instances) if !shouldLaunchInstances(instances) =>
      logger.info(s"Ignoring offer ${offer.getId.getValue}: ${status(instances)}")
      val readable = instances
        .map(i => s"${i.instanceId}:{condition: ${i.state.condition}, version: ${i.runSpecVersion}}")
        .mkString(", ")
      logger.info(s"Ignoring offer ${offer.getId.getValue} for $readable with should launch ${shouldLaunchInstances(instances)}")
      promise.trySuccess(OfferMatchResult.NoMatch(runSpec, offer, Seq.empty, clock.now()))

    // TODO(katsten): BE AWARE! The we do *not* use the instance map of this actor! Eventually this logic will move into TaskLauncherOfferMatcher.
    case TaskLauncherActor.MatchOffer(offer, promise, instances) =>
      val reachableInstances = instances.filterNot { instance => instance.state.condition.isLost || instance.isScheduled }
      val scheduledInstances = instances.filter(_.isScheduled)

      logger.info(s"Matching offer ${offer.getId} and need to launch ${scheduledInstances.size} tasks.")
      val readable = instances
        .map(i => s"${i.instanceId}:{condition: ${i.state.condition}, version: ${i.runSpecVersion}}")
        .mkString(", ")
      logger.info(s"Matching offer ${offer.getId} for $readable and should launch ${shouldLaunchInstances(instances)}")

      val matchRequest = InstanceOpFactory.Request(runSpec, offer, reachableInstances, scheduledInstances, localRegion())
      val matchResult = instanceOpFactory.matchOfferRequest(matchRequest)
      offerMatchStatisticsActor ! matchResult
      promise.trySuccess(matchResult)
  }

  // TODO(karsten): Remove this method
  def syncInstances(): Unit = syncInstances(instanceTracker.specInstancesSync(runSpec.id))

  def syncInstances(instances: Seq[Instance]): Unit = {
    // TODO(karsten): We may be able to not use a map.
    instanceMap = instances.map { instance => instance.instanceId -> instance }.toMap
    val readable = instanceMap.values
      .map(i => s"${i.instanceId}:{condition: ${i.state.condition}, version: ${i.runSpecVersion}}")
      .mkString(", ")
    logger.info(s"Synced instance map to $readable")
  }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())

  private[this] def shouldLaunchInstances(instances: Iterable[Instance]): Boolean = {
    val reservedInstances = instances.filter(_.isReserved)
    val scheduledInstances = instances.filter(_.isScheduled)

    (scheduledInstances.nonEmpty || reservedInstances.nonEmpty) && !backoffActive
  }

  private[this] def status(instances: Iterable[Instance]): String = {
    val backoffStr = backOffUntil match {
      case Some(until) if until > clock.now() => s"currently waiting for backoff($until)"
      case _ => "not backing off"
    }

    val inFlight = instances.count(_.isProvisioned)
    val activeInstances = instances.count(_.isActive)
    s"${instancesToLaunch(instances)} instancesToLaunch, $inFlight in flight, $activeInstances confirmed. $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if there are empty reservations or scheduled instances. */
  private[this] object OfferMatcherRegistration {

    private[this] val myselfAsOfferMatcher: base.OfferMatcher = new TaskLauncherActor.OfferMatcher(self, runSpec, instanceTracker)

    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchInstances(instanceMap.values)

      if (shouldBeRegistered && !registeredAsMatcher) {
        logger.debug(s"Registering for ${runSpec.id}, ${runSpec.version}.")
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      } else if (!shouldBeRegistered && registeredAsMatcher) {
        if (instancesToLaunch(instanceMap.values) > 0) {
          logger.info(s"Backing off due to task failures. Stop receiving offers for ${runSpec.id}, ${runSpec.version}")
        } else {
          logger.info(s"No tasks left to launch. Stop receiving offers for ${runSpec.id}, ${runSpec.version}")
        }
        unregister()
      }
    }

    def unregister(): Unit = {
      if (registeredAsMatcher) {
        logger.info("Deregister as matcher.")
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }
  }
}