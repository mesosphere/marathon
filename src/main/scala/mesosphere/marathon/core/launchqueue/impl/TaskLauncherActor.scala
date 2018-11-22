package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.scaladsl.SourceQueue
import java.time.Clock

import akka.Done
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstanceUpdateOperation}
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.matcher.base.util.{ActorOfferMatcher, InstanceOpSourceDelegate}
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{Region, RunSpec, Timestamp}
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
    runSpec: RunSpec): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      instanceTracker, rateLimiterActor, offerMatchStatistics,
      runSpec, localRegion))
  }

  sealed trait Requests

  case class Sync(runSpec: RunSpec) extends Requests

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

    private[impl] var runSpec: RunSpec,
    localRegion: () => Option[Region]) extends Actor with StrictLogging with Stash {
  // scalastyle:on parameter.number

  /** instances that are in the tracker */
  private[impl] var instanceMap: Map[Instance.Id, Instance] = _

  private[impl] def inFlightInstanceOperations = instanceMap.values.filter(_.isProvisioned)

  private[this] val provisionTimeouts = mutable.Map.empty[Instance.Id, Cancellable]

  private[impl] def scheduledInstances: Iterable[Instance] = instanceMap.values.filter(_.isScheduled)

  def instancesToLaunch = scheduledInstances.size

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[impl] var backOffUntil: Option[Timestamp] = None

  /** Decorator to use this actor as a [[OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = InstanceOpSourceDelegate(self)

  private[this] val startedAt = clock.now()

  override def preStart(): Unit = {
    super.preStart()

    syncInstances()

    logger.info(s"Started instanceLaunchActor for ${runSpec.id} version ${runSpec.version} with initial count $instancesToLaunch")
    rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightInstanceOperations.nonEmpty) {
      logger.warn(s"Actor shutdown while instances are in flight: ${inFlightInstanceOperations.map(_.instanceId).mkString(", ")}")
    }
    provisionTimeouts.valuesIterator.foreach(_.cancel())

    offerMatchStatistics.offer(OfferMatchStatistics.LaunchFinished(runSpec.id))

    super.postStop()

    logger.info(s"Stopped InstanceLauncherActor for ${runSpec.id} version ${runSpec.version}")
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiter.DelayUpdate(ref, delayUntil) if ref == runSpec.configRef =>
      logger.info(s"Got delay update for run spec ${ref.id}")
      stash()
      unstashAll()

      OfferMatcherRegistration.manageOfferMatcherStatus()
      context.become(active)
    case msg @ RateLimiter.DelayUpdate(ref, delayUntil) if ref != runSpec.configRef =>
      logger.warn(s"Received delay update for other run spec ${ref} and delay $delayUntil. Current run spec is ${runSpec.configRef}")
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveSync,
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
    case RateLimiter.DelayUpdate(ref, maybeDelayUntil) if ref == runSpec.configRef =>
      val delayUntil = maybeDelayUntil.getOrElse(clock.now())

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

      logger.debug(s"After delay update $status")

    case msg @ RateLimiter.DelayUpdate(ref, delayUntil) if ref != runSpec.configRef =>
      logger.warn(s"BUG! Received delay update for other run spec ${ref} and delay $delayUntil. Current run spec is ${runSpec.configRef}")

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
            if (runSpec.isResident) {
              await(instanceTracker.process(RescheduleReserved(instance, runSpec.version)))
            } else {
              // Forget about old instance and schedule new one.
              await(instanceTracker.forceExpunge(instance.instanceId)): @silent
              await(instanceTracker.schedule(Instance.scheduled(runSpec)))
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
    case update: InstanceDeleted =>
      logger.info(s"receiveInstanceUpdate: ${update.id} was deleted (${update.condition})")
      // A) If the app has constraints, we need to reconsider offers that
      // we already rejected. E.g. when a host:unique constraint prevented
      // us to launch tasks on a particular node before, we need to reconsider offers
      // of that node after a task on that node has died.
      //
      // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
      if (runSpec.constraints.nonEmpty || (runSpec.isResident && shouldLaunchInstances)) {
        maybeOfferReviver.foreach(_.reviveOffers())
      }
      syncInstance(update.instance.instanceId)
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done

    case change: InstanceChange =>
      syncInstance(change.instance.instanceId)
      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done

  }

  /**
    * Update internal instance map.
    */
  private[this] def receiveSync: Receive = {
    case TaskLauncherActor.Sync(newRunSpec) =>
      val configChange = runSpec.isUpgrade(newRunSpec)
      if (configChange || runSpec.needsRestart(newRunSpec)) {
        logger.info(s"Received new run spec for ${newRunSpec.id} old version ${runSpec.version} to new version ${newRunSpec.version}")

        runSpec = newRunSpec // Side effect for suspendMatchingUntilWeGetBackoffDelayUpdate

        if (configChange) {
          suspendMatchingUntilWeGetBackoffDelayUpdate()
        }
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()
      sender() ! Done
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

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(offer, promise) if !shouldLaunchInstances =>
      logger.debug(s"Ignoring offer ${offer.getId.getValue}: $status")
      promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))

    case ActorOfferMatcher.MatchOffer(offer, promise) =>
      logger.info(s"Matching offer ${offer.getId} and need to launch $instancesToLaunch tasks.")
      val reachableInstances = instanceMap.filterNotAs{
        case (_, instance) => instance.state.condition.isLost || instance.isScheduled
      }
      val matchRequest = InstanceOpFactory.Request(runSpec, offer, reachableInstances, scheduledInstances, localRegion())
      instanceOpFactory.matchOfferRequest(matchRequest) match {
        case matched: OfferMatchResult.Match =>
          logger.info(s"Matched offer ${offer.getId} for run spec ${runSpec.id}, ${runSpec.version}.")
          offerMatchStatistics.offer(OfferMatchStatistics.MatchResult(matched))
          handleInstanceOp(matched.instanceOp, offer, promise)
        case notMatched: OfferMatchResult.NoMatch =>
          logger.info(s"Did not match offer ${offer.getId} for run spec ${runSpec.id}, ${runSpec.version}.")
          offerMatchStatistics.offer(OfferMatchStatistics.MatchResult(notMatched))
          promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))
      }
  }

  def syncInstances(): Unit = {
    instanceMap = instanceTracker.instancesBySpecSync.instancesMap(runSpec.id).instanceMap
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
      case None =>
        instanceMap -= instanceId
        provisionTimeouts.get(instanceId).foreach(_.cancel())
        provisionTimeouts -= instanceId
        logger.info(s"$instanceId does not exist in InstanceTracker - removing it from internal state.")
    }
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
      case InstanceUpdateOperation.Provision(instance) =>
        assert(instanceMap.contains(instance.instanceId), s"Internal task launcher state did not include provisioned instance ${instance.instanceId}")
        instanceMap += instance.instanceId -> instance
        scheduleTaskOpTimeout(context, instanceOp)
        logger.info(s"Updated instance map to ${instanceMap.values.map(i => i.instanceId -> i.state.condition)}")
      case InstanceUpdateOperation.Reserve(instance) =>
        assert(instanceMap.contains(instance.instanceId), s"Internal task launcher state did not include reserved instance ${instance.instanceId}")
        instanceMap += instance.instanceId -> instance
        scheduleTaskOpTimeout(context, instanceOp)
        logger.info(s"Updated instance map to ${instanceMap.values.map(i => i.instanceId -> i.state.condition)}")
      case other =>
        logger.info(s"Unexpected updated operation $other")
    }

    OfferMatcherRegistration.manageOfferMatcherStatus()

    logger.info(s"Request ${instanceOp.getClass.getSimpleName} for instance '${instanceOp.instanceId.idString}', version '${runSpec.version}'. $status")
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

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchInstances: Boolean = scheduledInstances.nonEmpty && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(until) if until > clock.now() => s"currently waiting for backoff($until)"
      case _ => "not backing off"
    }

    val inFlight = inFlightInstanceOperations.size
    val launchedInstances = instanceMap.values.filterNot(_.isProvisioned).count(_.isActive)
    s"$instancesToLaunch instancesToLaunch, $inFlight in flight, $launchedInstances confirmed. $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if there are empty reservations or scheduled instances. */
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = {
      //set the precedence only, if this app is resident
      new ActorOfferMatcher(self, if (runSpec.isResident) Some(runSpec.id) else None)
    }
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchInstances

      if (shouldBeRegistered && !registeredAsMatcher) {
        logger.debug(s"Registering for ${runSpec.id}, ${runSpec.version}.")
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      } else if (!shouldBeRegistered && registeredAsMatcher) {
        if (instancesToLaunch > 0) {
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
