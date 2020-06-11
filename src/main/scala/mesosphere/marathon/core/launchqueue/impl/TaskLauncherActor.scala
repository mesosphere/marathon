package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock

import akka.Done
import akka.actor._
import akka.event.LoggingReceive
import akka.stream.scaladsl.SourceQueue
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdateOperation, InstanceUpdated}
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.matcher.base.util.{ActorOfferMatcher, InstanceOpSourceDelegate}
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import scala.jdk.CollectionConverters._
import mesosphere.marathon.util.CancellableOnce
import org.apache.mesos.{Protos => Mesos}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._

private[launchqueue] object TaskLauncherActor {
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskOpFactory: InstanceOpFactory,
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatistics: SourceQueue[OfferMatchStatistics.OfferMatchUpdate],
    localRegion: () => Option[Region])(
    runSpecId: AbsolutePathId): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      instanceTracker, rateLimiterActor, offerMatchStatistics,
      runSpecId, localRegion))
  }

  sealed trait Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  val OfferOperationRejectedTimeoutReason: String =
    "InstanceLauncherActor: no accept received within timeout. " +
      "You can reconfigure the timeout with --task_operation_notification_timeout."
}

/**
  * Allows processing offers for starting tasks for the given app.
  */
private class TaskLauncherActor(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    instanceOpFactory: InstanceOpFactory,
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatistics: SourceQueue[OfferMatchStatistics.OfferMatchUpdate],
    runSpecId: AbsolutePathId,
    localRegion: () => Option[Region]) extends Actor with StrictLogging with Stash {
  // scalastyle:on parameter.number

  /** instances that are in the tracker */
  private[impl] var instanceMap: Map[Instance.Id, Instance] = _

  private[impl] def inFlightInstanceOperations = instanceMap.values.filter(_.isProvisioned)

  private[impl] val offerOperationAcceptTimeout = mutable.Map.empty[Instance.Id, Cancellable]

  private[impl] def scheduledInstances: Iterable[Instance] = instanceMap.values.filter(_.isScheduled)
  def scheduledVersions = scheduledInstances.map(_.runSpec.configRef).toSet

  def instancesToLaunch = scheduledInstances.size

  private[this] var recheckBackOff: Cancellable = CancellableOnce.noop

  /* A map of times a RunSpec version is allowed to launch. Sourced from RateLimiter DelayUpdate.
   * When we receive signal that there is no delay, the current time is used.
   */
  val launchAllowedAt: mutable.Map[RunSpecConfigRef, Timestamp] = mutable.Map.empty

  /** Decorator to use this actor as a [[OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = InstanceOpSourceDelegate(self)

  override def preStart(): Unit = {
    super.preStart()

    syncInstances()

    logger.info(s"Started instanceLaunchActor for ${runSpecId} with initial count $instancesToLaunch")
    scheduledVersions.foreach { configRef =>
      rateLimiterActor ! RateLimiterActor.GetDelay(configRef)
    }
  }

  override def postStop(): Unit = {
    unregister()
    recheckBackOff.cancel()

    if (inFlightInstanceOperations.nonEmpty) {
      logger.warn(s"Actor shutdown while instances are in flight: ${inFlightInstanceOperations.map(_.instanceId).mkString(", ")}")
    }
    offerOperationAcceptTimeout.valuesIterator.foreach(_.cancel())

    offerMatchStatistics.offer(OfferMatchStatistics.LaunchFinished(runSpecId))

    super.postStop()

    logger.info(s"Stopped InstanceLauncherActor for ${runSpecId}.")
  }

  override def receive: Receive = active

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveInstanceUpdate,
      receiveProcessOffers,
      receiveUnknown
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def receiveUnknown: Receive = {
    case Status.Failure(ex) =>
      logger.error(s"TaskLauncherActor received a failure from ${sender}", ex)

    case msg: Any =>
      // fail fast and do not let the sender time out
      sender() ! Status.Failure(new IllegalStateException(s"Unhandled message: $msg"))
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiter.DelayUpdate(ref, maybeDelayUntil) if scheduledVersions.contains(ref) =>
      val now = clock.now()
      // If there's no delay, then launch immediately
      launchAllowedAt += ref -> maybeDelayUntil.map(_.deadline).getOrElse(now)

      manageOfferMatcherStatus(now)

      scheduleNextManageOfferStatus(now)

      logger.debug(s"After delay update $status")

    case msg @ RateLimiter.DelayUpdate(ref, delayUntil) if ref.id != runSpecId =>
      logger.warn(s"BUG! Received delay update for other run spec $ref and delay $delayUntil. Current run spec is $runSpecId")

    case RecheckIfBackOffUntilReached => manageOfferMatcherStatus(clock.now())
  }

  private[this] def scheduleNextManageOfferStatus(now: Timestamp): Unit = {
    recheckBackOff.cancel()
    val pendingBackOffs = launchAllowedAt.values.filter { _ > now }
    if (pendingBackOffs.nonEmpty) {
      val nextCheck = pendingBackOffs.min
      recheckBackOff =
        context.system.scheduler.scheduleOnce(now.until(nextCheck), self, RecheckIfBackOffUntilReached)(context.dispatcher)
    }
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason) =>
      if (inFlightInstanceOperations.exists(_.instanceId == op.instanceId) && offerOperationAcceptTimeout.contains(op.instanceId)) {
        // TaskLauncherActor is not always in sync with the state in InstanceTracker.
        // Especially when accepting offer, its state is ahead of InstanceTracker because it marks the instance as provisioned even before we persist it.
        // We do this because we need to know that we already attempted to launch that instance to not use the same one for another offer.
        // If everything goes well, some time after we manually adjust the internal state we should receive instance update to Provisioned.
        // It might happen that the we were not able to persist that Provisioned operation for some reason.
        // This timeout is here just to prevent us from staying in a state where we have different state locally than in InstanceTracker
        // This timeout should be set that far in the future that the offer operation should have already timed out anyway so it should be safe to do.
        // By explicitly syncing here we would either get the instance in Provisioned (we persisted the update) or scheduled (something failed).
        syncInstance(op.instanceId)
      }

    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) =>
      logger.debug(s"Task op '${op.getClass.getSimpleName}' for ${op.instanceId} was REJECTED, reason '$reason', rescheduling. $status")
      syncInstance(op.instanceId)

    case InstanceOpSourceDelegate.InstanceOpAccepted(op) =>
      logger.debug(s"Instance operation ${op.getClass.getSimpleName} for instance ${op.instanceId} got accepted")
      offerOperationAcceptTimeout.get(op.instanceId).foreach(_.cancel())
      offerOperationAcceptTimeout -= op.instanceId
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case update: InstanceUpdated =>
      syncInstance(update.instance.instanceId)
      sender() ! Done

    case update: InstanceDeleted =>
      // if an instance was deleted, it's not needed anymore and we only have to remove it from the internal state
      logger.info(s"${update.instance.instanceId} was deleted. Will remove from internal state.")
      removeInstanceFromInternalState(update.instance.instanceId)
      sender() ! Done
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(offer, promise) if !shouldLaunchInstances(clock.now()) =>
      logger.debug(s"Ignoring offer ${offer.getId.getValue}: $status")
      promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))

    case ActorOfferMatcher.MatchOffer(offer, promise) =>
      logger.info(s"Matching offer ${offer.getId.getValue} and need to launch $instancesToLaunch tasks.")
      val reachableInstances = instanceMap.filterNot {
        case (_, instance) =>
          instance.state.condition.isLost || instance.isScheduled
      }
      val candidateInstances = scheduledInstances.iterator
        .filter { instance => offer.getAllocationInfo.getRole == instance.role }
        .filter { instance => launchAllowed(clock.now(), instance.runSpec.configRef) }
        .toSeq

      candidateInstances match {
        case NonEmptyIterable(scheduledInstancesWithoutBackoff) =>
          val matchRequest = InstanceOpFactory.Request(offer, reachableInstances, scheduledInstancesWithoutBackoff, localRegion())
          instanceOpFactory.matchOfferRequest(matchRequest) match {
            case matched: OfferMatchResult.Match =>
              logger.info(s"Matched offer ${offer.getId.getValue} for run spec ${runSpecId}.")
              offerMatchStatistics.offer(OfferMatchStatistics.MatchResult(matched))
              handleInstanceOp(matched.instanceOp, offer, promise)
            case notMatched: OfferMatchResult.NoMatch =>
              logger.info(s"Did not match offer ${offer.getId.getValue} for run spec ${runSpecId}. Reasons: ${notMatched.reasons}")
              offerMatchStatistics.offer(OfferMatchStatistics.MatchResult(notMatched))
              promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))
          }
        case _ =>
          logger.debug(s"Ignoring offer ${offer.getId.getValue}: $status")
          promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))
      }
  }

  def syncInstances(): Unit = {
    instanceMap = instanceTracker.instancesBySpecSync.instancesMap(runSpecId).instanceMap
    val readable = instanceMap.values
      .map(i => s"${i.instanceId}:{condition: ${i.state.condition}, goal: ${i.state.goal}, version: ${i.runSpecVersion}, reservation: ${i.reservation}}")
      .mkString(", ")
    logger.info(s"Synced instance map to $readable")
  }

  def syncInstance(instanceId: Instance.Id): Unit = {
    instanceTracker.instancesBySpecSync.instance(instanceId) match {
      case Some(instance) =>
        instanceMap += instanceId -> instance

        // This timeout is only between us creating provisioned instance operation based on received offer
        // and that instance in provisioned state being persisted so this makes no sense for instances in all other states
        if (!instance.isProvisioned) {
          offerOperationAcceptTimeout.get(instanceId).foreach(_.cancel())
          offerOperationAcceptTimeout -= instanceId
        }
        logger.info(s"Synced single $instanceId from InstanceTracker: $instance")

        // Request delay for new run spec config.
        if (!launchAllowedAt.contains(instance.runSpec.configRef)) {
          // signal no interest in new offers until we get the back off delay.
          // this makes sure that we see unused offers again that we rejected for the old configuration.
          unregister()
          rateLimiterActor ! RateLimiterActor.GetDelay(instance.runSpec.configRef)
        }
      case None =>
        logger.info(s"Instance $instanceId does not exist in InstanceTracker - removing it from internal state.")
        removeInstanceFromInternalState(instanceId)
    }

    manageOfferMatcherStatus(clock.now())
  }

  def removeInstanceFromInternalState(instanceId: Instance.Id): Unit = {
    instanceMap -= instanceId
    offerOperationAcceptTimeout.get(instanceId).foreach(_.cancel())
    offerOperationAcceptTimeout -= instanceId

    // Remove backoffs for deleted config refs
    launchAllowedAt.keySet.diff(scheduledVersions).foreach(launchAllowedAt.remove)
    manageOfferMatcherStatus(clock.now())

  }

  /**
    * Mutate internal state in response to having matched an instanceOp.
    *
    * @param instanceOp The instanceOp that is to be applied to on a previously
    *     received offer
    * @param offer The offer that could be matched successfully.
    * @param promise Promise that tells offer matcher that the offer has been accepted.
    */
  private[this] def handleInstanceOp(instanceOp: InstanceOp, offer: Mesos.Offer, promise: Promise[MatchedInstanceOps]): Unit = {

    // Mark instance in internal map as provisioned
    instanceOp.stateOp match {
      case InstanceUpdateOperation.Provision(instanceId, agentInfo, runSpecVersion, tasks, now) =>
        assert(instanceMap.contains(instanceId), s"Internal task launcher state did not include newly provisioned instance $instanceId")
        val existingInstance = instanceMap(instanceId)
        instanceMap += instanceId -> existingInstance.provisioned(agentInfo, runSpecVersion, tasks, now)
        scheduleTaskOpTimeout(context, instanceOp)
        logger.info(s"Updated instance map to ${instanceMap.values.map(i => i.instanceId -> i.state.condition)}")
      case InstanceUpdateOperation.Reserve(instanceId, reservation, agentInfo) =>
        assert(instanceMap.contains(instanceId), s"Internal task launcher state did not include reserved instance $instanceId")
        val existingInstance = instanceMap(instanceId)
        instanceMap += instanceId -> existingInstance.reserved(reservation, agentInfo)
        scheduleTaskOpTimeout(context, instanceOp)
        logger.info(s"Updated instance map to reserve ${instanceMap.values.map(i => i.instanceId -> i.state.condition)}")
      case other =>
        logger.info(s"Unexpected updated operation $other")
    }

    manageOfferMatcherStatus(clock.now())

    logger.info(s"Request ${instanceOp.getClass.getSimpleName} for instance '${instanceOp.instanceId.idString}'. $status")
    promise.trySuccess(MatchedInstanceOps(offer.getId, Seq(InstanceOpWithSource(myselfAsLaunchSource, instanceOp))))
  }

  private[this] def scheduleTaskOpTimeout(
    context: ActorContext,
    instanceOp: InstanceOp): Unit =
    {
      import context.dispatcher
      val message: InstanceOpSourceDelegate.InstanceOpRejected = InstanceOpSourceDelegate.InstanceOpRejected(
        instanceOp, TaskLauncherActor.OfferOperationRejectedTimeoutReason
      )
      val scheduledProvisionTimeout = context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, message)
      offerOperationAcceptTimeout += instanceOp.instanceId -> scheduledProvisionTimeout
    }

  /**
    * Returns the time at which we know we can launch this instance
    *
    * Since this actor subscribes to task delays asynchronously, we pessimistically assume that an instance cannot be
    * launched until we've received a DelayUpdate for the respective config version
    */
  private[this] def launchAllowed(now: Timestamp, configRef: RunSpecConfigRef): Boolean = launchAllowedAt.get(configRef).exists(_ <= now)
  private[this] def shouldLaunchInstances(now: Timestamp): Boolean = {
    if (scheduledInstances.nonEmpty || launchAllowedAt.nonEmpty)
      logger.info(s"Found scheduled instances: ${scheduledInstances.map(_.instanceId).mkString(",")} and current back-off map: $launchAllowedAt")
    scheduledInstances.nonEmpty && scheduledVersions.exists { configRef => launchAllowed(now, configRef) }
  }

  private[this] def status: String = {
    val activeBackOffs = launchAllowedAt.values.filter(_ > clock.now())
    val backoffStr = if (activeBackOffs.nonEmpty) {
      s"currently waiting for backoffs $activeBackOffs"
    } else {
      "not backing off"
    }

    val inFlight = inFlightInstanceOperations.size
    val launchedInstances = instanceMap.values.filterNot(_.isProvisioned).count(_.isActive)
    s"$instancesToLaunch instancesToLaunch, $inFlight in flight, $launchedInstances confirmed. $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if there are empty reservations or scheduled instances. */
  private[this] lazy val myselfAsOfferMatcher: OfferMatcher = {
    //set the precedence only, if this app is resident
    val isResident = scheduledInstances.exists(_.runSpec.isResident)
    new ActorOfferMatcher(self, if (isResident) Some(runSpecId) else None)
  }
  private[this] var registeredAsMatcher = false

  /** Register/unregister as necessary */
  private[this] def manageOfferMatcherStatus(now: Timestamp): Unit = {
    val shouldBeRegistered = shouldLaunchInstances(now)

    if (shouldBeRegistered && !registeredAsMatcher) {
      logger.debug(s"Registering for ${runSpecId}.")
      offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
      registeredAsMatcher = true
    } else if (!shouldBeRegistered && registeredAsMatcher) {
      if (instancesToLaunch > 0) {
        logger.info(s"Backing off due to task failures. Stop receiving offers for ${runSpecId}")
      } else {
        logger.info(s"No tasks left to launch. Stop receiving offers for ${runSpecId}")
      }
      unregister()
    }
  }

  private[this] def unregister(): Unit = {
    if (registeredAsMatcher) {
      logger.info("Deregister as matcher.")
      offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
      registeredAsMatcher = false
    }
  }
}
