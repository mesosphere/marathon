package mesosphere.marathon
package core.launchqueue.impl

import java.time.Clock

import akka.Done
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.pipe
import akka.stream.scaladsl.SourceQueue
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
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
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.{Protos => Mesos}

import scala.async.Async.{async, await}
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._

private[launchqueue] object TaskLauncherActor {
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskOpFactory: InstanceOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatistics: SourceQueue[OfferMatchStatistics.OfferMatchUpdate],
    localRegion: () => Option[Region])(
    runSpecId: PathId): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      instanceTracker, rateLimiterActor, offerMatchStatistics,
      runSpecId, localRegion))
  }

  sealed trait Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  case object Stop extends Requests

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
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,
    offerMatchStatistics: SourceQueue[OfferMatchStatistics.OfferMatchUpdate],
    runSpecId: PathId,
    localRegion: () => Option[Region]) extends Actor with StrictLogging with Stash {
  // scalastyle:on parameter.number

  /** instances that are in the tracker */
  private[impl] var instanceMap: Map[Instance.Id, Instance] = _

  private[impl] def inFlightInstanceOperations = instanceMap.values.filter(_.isProvisioned)

  private[this] val provisionTimeouts = mutable.Map.empty[Instance.Id, Cancellable]

  private[impl] def scheduledInstances: Iterable[Instance] = instanceMap.values.filter(_.isScheduled)
  def scheduledVersions = scheduledInstances.map(_.runSpec.configRef).toSet

  def instancesToLaunch = scheduledInstances.size

  private[this] var recheckBackOff: Option[Cancellable] = None

  // A map of run spec version to back offs.
  val backOffs: mutable.Map[RunSpecConfigRef, Timestamp] = mutable.Map.empty

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
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightInstanceOperations.nonEmpty) {
      logger.warn(s"Actor shutdown while instances are in flight: ${inFlightInstanceOperations.map(_.instanceId).mkString(", ")}")
    }
    provisionTimeouts.valuesIterator.foreach(_.cancel())

    offerMatchStatistics.offer(OfferMatchStatistics.LaunchFinished(runSpecId))

    super.postStop()

    logger.info(s"Stopped InstanceLauncherActor for ${runSpecId}.")
  }

  override def receive: Receive = active

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveInstanceUpdate,
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
      if (inFlightInstanceOperations.nonEmpty) {
        val taskIds = inFlightInstanceOperations.take(3).map(_.instanceId).mkString(", ")
        logger.info(
          s"Still waiting for ${inFlightInstanceOperations.size} inflight messages but stopping anyway. " +
            s"First three task ids: $taskIds"
        )
      }
      context.stop(self)
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiter.DelayUpdate(ref, maybeDelayUntil) if scheduledVersions.contains(ref) =>
      val delayUntil = maybeDelayUntil.getOrElse(clock.now())
      logger.debug(s"Received backkoff $delayUntil for $ref")

      if (!backOffs.get(ref).contains(delayUntil)) {
        backOffs += ref -> delayUntil

        recheckBackOff.foreach(_.cancel())
        recheckBackOff = None

        val now: Timestamp = clock.now()
        if (backOffs.get(ref).exists(_ > now)) {
          import context.dispatcher
          recheckBackOff = Some(
            context.system.scheduler.scheduleOnce(now until delayUntil, self, RecheckIfBackOffUntilReached)
          )
        }

        OfferMatcherRegistration.manageOfferMatcherStatus()
      }

      logger.debug(s"After delay update $status")

    case msg @ RateLimiter.DelayUpdate(ref, delayUntil) if ref.id != runSpecId =>
      logger.warn(s"BUG! Received delay update for other run spec $ref and delay $delayUntil. Current run spec is $runSpecId")

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason) =>
      // Reschedule instance with provision timeout..
      if (inFlightInstanceOperations.exists(_.instanceId == op.instanceId)) {
        instanceMap.get(op.instanceId).foreach { instance =>
          import scala.concurrent.ExecutionContext.Implicits.global

          logger.info(s"Reschedule ${instance.instanceId} because of provision timeout.")
          async {
            if (instance.runSpec.isResident) {
              await(instanceTracker.process(RescheduleReserved(instance, instance.runSpec)))
            } else {
              // Forget about old instance and schedule new one.
              await(instanceTracker.forceExpunge(instance.instanceId)): @silent
              await(instanceTracker.schedule(Instance.scheduled(instance.runSpec)))
            }
          } pipeTo self
        }
      }

    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) =>
      logger.debug(s"Task op '${op.getClass.getSimpleName}' for ${op.instanceId} was REJECTED, reason '$reason', rescheduling. $status")
      syncInstance(op.instanceId)
      OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case update: InstanceUpdated =>
      if (update.condition.isTerminal & update.instance.isScheduled) {
        logger.info(s"receiveInstanceUpdate: ${update.id} is terminal (${update.condition}) and scheduled.")
        // A) If the app has constraints, we need to reconsider offers that
        // we already rejected. E.g. when a host:unique constraint prevented
        // us to launch tasks on a particular node before, we need to reconsider offers
        // of that node after a task on that node has died.
        //
        // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
        val runSpec = update.instance.runSpec
        if (runSpec.constraints.nonEmpty || (runSpec.isResident && shouldLaunchInstances)) {
          maybeOfferReviver.foreach(_.reviveOffers())
        }
      }
      syncInstance(update.instance.instanceId)
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done

    case update: InstanceDeleted =>
      // if an instance was deleted, it's not needed anymore and we only have to remove it from the internal state
      logger.info(s"${update.instance.instanceId} was deleted. Will remove from internal state.")
      removeInstanceFromInternalState(update.instance.instanceId)
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(offer, promise) if !shouldLaunchInstances =>
      logger.debug(s"Ignoring offer ${offer.getId.getValue}: $status")
      promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))

    case ActorOfferMatcher.MatchOffer(offer, promise) =>
      logger.info(s"Matching offer ${offer.getId.getValue} and need to launch $instancesToLaunch tasks.")
      val reachableInstances = instanceMap.filterNotAs{
        case (_, instance) => instance.state.condition.isLost || instance.isScheduled
      }
      scheduledInstances.filterNot(i => backoffActive(i.runSpec.configRef)) match {
        case NonEmptyIterable(scheduledInstancesWithoutBackoff) =>
          val matchRequest = InstanceOpFactory.Request(offer, reachableInstances, scheduledInstancesWithoutBackoff, localRegion())
          instanceOpFactory.matchOfferRequest(matchRequest) match {
            case matched: OfferMatchResult.Match =>
              logger.info(s"Matched offer ${offer.getId.getValue} for run spec ${runSpecId}.")
              offerMatchStatistics.offer(OfferMatchStatistics.MatchResult(matched))
              handleInstanceOp(matched.instanceOp, offer, promise)
            case notMatched: OfferMatchResult.NoMatch =>
              logger.info(s"Did not match offer ${offer.getId.getValue} for run spec ${runSpecId}.")
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

        // Only instances that scheduled or provisioned have not seen a Mesos update. The provision timeouts waits for
        // any Mesos update. Thus we can safely kill the provision timeout in all other cases, even on a TASK_FAILED.
        if (!instance.isProvisioned && !instance.isScheduled) {
          provisionTimeouts.get(instanceId).foreach(_.cancel())
          provisionTimeouts -= instanceId
        }
        logger.info(s"Synced single $instanceId from InstanceTracker: $instance")

        // Request delay for new run spec config.
        if (!backOffs.contains(instance.runSpec.configRef)) {
          logger.debug(s"Requesting backoff delay for ${instance.runSpec.configRef}")
          // signal no interest in new offers until we get the back off delay.
          // this makes sure that we see unused offers again that we rejected for the old configuration.
          OfferMatcherRegistration.unregister()
          rateLimiterActor ! RateLimiterActor.GetDelay(instance.runSpec.configRef)
        }
      case None =>
        logger.info(s"Instance $instanceId does not exist in InstanceTracker - removing it from internal state.")
        removeInstanceFromInternalState(instanceId)
    }
  }

  def removeInstanceFromInternalState(instanceId: Instance.Id): Unit = {
    instanceMap -= instanceId
    provisionTimeouts.get(instanceId).foreach(_.cancel())
    provisionTimeouts -= instanceId

    // Remove backoffs for deleted config refs
    backOffs.keySet.diff(scheduledVersions).foreach(backOffs.remove)
    OfferMatcherRegistration.manageOfferMatcherStatus()

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
      case InstanceUpdateOperation.Reserve(instance) =>
        assert(instanceMap.contains(instance.instanceId), s"Internal task launcher state did not include reserved instance ${instance.instanceId}")
        instanceMap += instance.instanceId -> instance
        scheduleTaskOpTimeout(context, instanceOp)
        logger.info(s"Updated instance map to reserve ${instanceMap.values.map(i => i.instanceId -> i.state.condition)}")
      case other =>
        logger.info(s"Unexpected updated operation $other")
    }

    OfferMatcherRegistration.manageOfferMatcherStatus()

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
      provisionTimeouts += instanceOp.instanceId -> scheduledProvisionTimeout
    }

  //TODO(karsten): We may want to change it to `!backOffs.get(configRef).exists(clock.now() < _)` so that instances without a defined back off do not start.
  private[this] def backoffActive(configRef: RunSpecConfigRef): Boolean = backOffs.get(configRef).forall(_ > clock.now())
  private[this] def shouldLaunchInstances(): Boolean = {
    if (scheduledInstances.nonEmpty) logger.info(s"Scheduled instances: $scheduledInstances, backOffs: $backOffs")
    scheduledInstances.nonEmpty && scheduledVersions.exists { configRef => !backoffActive(configRef) }
  }

  private[this] def status: String = {
    val activeBackOffs = backOffs.values.filter(_ > clock.now())
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
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = {
      //set the precedence only, if this app is resident
      val isResident = scheduledInstances.exists(_.runSpec.isResident)
      new ActorOfferMatcher(self, if (isResident) Some(runSpecId) else None)
    }
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchInstances()

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

    def unregister(): Unit = {
      if (registeredAsMatcher) {
        logger.info("Deregister as matcher.")
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }
  }
}
