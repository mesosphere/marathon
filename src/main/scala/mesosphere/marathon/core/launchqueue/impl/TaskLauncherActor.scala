package mesosphere.marathon
package core.launchqueue.impl

import akka.Done
import akka.actor._
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceDeleted, InstanceUpdated }
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.InstanceOpNotification
import mesosphere.marathon.core.matcher.base.util.{ ActorOfferMatcher, InstanceOpSourceDelegate }
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ RunSpec, Timestamp }
import org.apache.mesos.{ Protos => Mesos }
import mesosphere.marathon.stream.Implicits._

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
    offerMatchStatisticsActor: ActorRef)(
    runSpec: RunSpec,
    initialCount: Int): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      instanceTracker, rateLimiterActor, offerMatchStatisticsActor,
      runSpec, initialCount))
  }

  sealed trait Requests

  /**
    * Increase the instance count of the receiver.
    * The actor responds with a [[QueuedInstanceInfo]] message.
    */
  case class AddInstances(spec: RunSpec, count: Int) extends Requests
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
    private[this] var instancesToLaunch: Int) extends Actor with StrictLogging with Stash {
  // scalastyle:on parameter.number

  private[this] var inFlightInstanceOperations = Map.empty[Instance.Id, Cancellable]

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  /** instances that are in flight and those in the tracker */
  private[this] var instanceMap: Map[Instance.Id, Instance] = _

  /** Decorator to use this actor as a [[OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = InstanceOpSourceDelegate(self)

  private[this] val startedAt = clock.now()

  override def preStart(): Unit = {
    super.preStart()

    logger.info(s"Started instanceLaunchActor for ${runSpec.id} version ${runSpec.version} with initial count $instancesToLaunch")

    instanceMap = instanceTracker.instancesBySpecSync.instancesMap(runSpec.id).instanceMap
    rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightInstanceOperations.nonEmpty) {
      logger.warn(s"Actor shutdown while instances are in flight: ${inFlightInstanceOperations.keys.mkString(", ")}")
      inFlightInstanceOperations.values.foreach(_.cancel())
    }

    offerMatchStatisticsActor ! OfferMatchStatisticsActor.LaunchFinished(runSpec.id)

    super.postStop()

    logger.info(s"Stopped InstanceLauncherActor for ${runSpec.id} version ${runSpec.version}")
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(spec, delayUntil) if spec == runSpec =>
      stash()
      unstashAll()
      context.become(active)
    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      logger.warn(s"Received delay update for other runSpec: $msg")
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveInstanceUpdate,
      receiveGetCurrentCount,
      receiveAddCount,
      receiveProcessOffers,
      receiveUnknown
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def stopping: Receive = LoggingReceive.withLabel("stopping") {
    Seq(
      receiveStop,
      receiveWaitingForInFlight,
      receiveUnknown
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def receiveWaitingForInFlight: Receive = {
    case notification: InstanceOpNotification =>
      receiveTaskLaunchNotification(notification)
      waitForInFlightIfNecessary()

    case TaskLauncherActor.Stop => // ignore, already stopping

    case "waitingForInFlight" => sender() ! "waitingForInFlight" // for testing
  }

  private[this] def receiveUnknown: Receive = {
    case msg: Any =>
      // fail fast and do not let the sender time out
      sender() ! Status.Failure(new IllegalStateException(s"Unhandled message: $msg"))
  }

  private[this] def receiveStop: Receive = {
    case TaskLauncherActor.Stop =>
      if (inFlightInstanceOperations.nonEmpty) {
        // try to stop gracefully but also schedule timeout
        import context.dispatcher
        logger.info(s"schedule timeout for stopping in ${config.taskOpNotificationTimeout().milliseconds}")
        context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, PoisonPill)
      }
      waitForInFlightIfNecessary()
  }

  private[this] def waitForInFlightIfNecessary(): Unit = {
    if (inFlightInstanceOperations.isEmpty) {
      context.stop(self)
    } else {
      val taskIds = inFlightInstanceOperations.keys.take(3).mkString(", ")
      logger.info(
        s"Stopping but still waiting for ${inFlightInstanceOperations.size} in-flight messages, " +
          s"first three task ids: $taskIds"
      )
      context.become(stopping)
    }
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

      logger.debug(s"After delay update $status")

    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      logger.warn(s"Received delay update for other runSpec: $msg")

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) if inFlight(op) =>
      removeInstance(op.instanceId)
      logger.debug(s"Task op '${op.getClass.getSimpleName}' for ${op.instanceId} was REJECTED, reason '$reason', rescheduling. $status")

      op match {
        // only increment for launch ops, not for reservations:
        case _: InstanceOp.LaunchTask => instancesToLaunch += 1
        case _: InstanceOp.LaunchTaskGroup => instancesToLaunch += 1
        case _ => ()
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()

    case InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason) =>
      // This is a message that we scheduled in this actor.
      // When we receive a launch confirmation or rejection, we cancel this timer but
      // there is still a race and we might send ourselves the message nevertheless, so we just
      // ignore it here.
      logger.debug(s"Ignoring task launch rejected for '${op.instanceId}' as the task is not in flight anymore")

    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) =>
      logger.warn(s"Unexpected task op '${op.getClass.getSimpleName}' rejected for ${op.instanceId} with reason $reason")

    case InstanceOpSourceDelegate.InstanceOpAccepted(op) =>
      inFlightInstanceOperations -= op.instanceId
      logger.debug(s"Task op '${op.getClass.getSimpleName}' for ${op.instanceId} was accepted. $status")
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case change: InstanceChange =>
      change match {
        case update: InstanceUpdated =>
          logger.debug(s"receiveInstanceUpdate: ${update.id} is ${update.condition}")
          instanceMap += update.id -> update.instance

        case update: InstanceDeleted =>
          logger.info(s"receiveInstanceUpdate: ${update.id} was deleted (${update.condition})")
          removeInstance(update.id)
          // A) If the app has constraints, we need to reconsider offers that
          // we already rejected. E.g. when a host:unique constraint prevented
          // us to launch tasks on a particular node before, we need to reconsider offers
          // of that node after a task on that node has died.
          //
          // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
          if (runSpec.constraints.nonEmpty || (runSpec.residency.isDefined && shouldLaunchInstances)) {
            maybeOfferReviver.foreach(_.reviveOffers())
          }
      }
      sender() ! Done
  }

  private[this] def removeInstance(instanceId: Instance.Id): Unit = {
    inFlightInstanceOperations.get(instanceId).foreach(_.cancel())
    inFlightInstanceOperations -= instanceId
    instanceMap -= instanceId
  }

  private[this] def receiveGetCurrentCount: Receive = {
    case TaskLauncherActor.GetCount =>
      replyWithQueuedInstanceCount()
  }

  private[this] def receiveAddCount: Receive = {
    case TaskLauncherActor.AddInstances(newRunSpec, addCount) =>
      val configChange = runSpec.isUpgrade(newRunSpec)
      if (configChange || runSpec.needsRestart(newRunSpec) || runSpec.isOnlyScaleChange(newRunSpec)) {
        runSpec = newRunSpec
        instancesToLaunch = addCount

        if (configChange) {
          logger.info(s"getting new runSpec for '${runSpec.id}', version ${runSpec.version} with $addCount initial instances")

          suspendMatchingUntilWeGetBackoffDelayUpdate()

        } else {
          logger.info(s"scaling change for '${runSpec.id}', version ${runSpec.version} with $addCount initial instances")
        }
      } else {
        logger.info(s"add {$addCount instances to $instancesToLaunch instances to launch")
        instancesToLaunch += addCount
      }

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
    val instancesLaunched = instanceMap.values.count(_.isLaunched)
    val instancesLaunchesInFlight = inFlightInstanceOperations.keys
      .count(instanceId => instanceMap.get(instanceId).exists(_.isLaunched))
    sender() ! QueuedInstanceInfo(
      runSpec,
      inProgress = instancesToLaunch > 0 || inFlightInstanceOperations.nonEmpty,
      instancesLeftToLaunch = instancesToLaunch,
      finalInstanceCount = instancesToLaunch + instancesLaunchesInFlight + instancesLaunched,
      backOffUntil.getOrElse(clock.now()),
      startedAt
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(offer, promise) if !shouldLaunchInstances =>
      logger.debug(s"Ignoring offer ${offer.getId.getValue}: $status")
      promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))

    case ActorOfferMatcher.MatchOffer(offer, promise) =>
      val reachableInstances = instanceMap.filterNotAs{ case (_, instance) => instance.state.condition.isLost }
      val matchRequest = InstanceOpFactory.Request(runSpec, offer, reachableInstances, instancesToLaunch)
      instanceOpFactory.matchOfferRequest(matchRequest) match {
        case matched: OfferMatchResult.Match =>
          offerMatchStatisticsActor ! matched
          handleInstanceOp(matched.instanceOp, offer, promise)
        case notMatched: OfferMatchResult.NoMatch =>
          offerMatchStatisticsActor ! notMatched
          promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId))
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
    def updateActorState(): Unit = {
      val instanceId = instanceOp.instanceId
      instanceOp match {
        // only decrement for launched instances, not for reservations:
        case _: InstanceOp.LaunchTask => instancesToLaunch -= 1
        case _: InstanceOp.LaunchTaskGroup => instancesToLaunch -= 1
        case _ => ()
      }

      // We will receive the updated instance once it's been persisted. Before that,
      // we can only store the possible state, as we don't have the updated state
      // yet.
      instanceOp.stateOp.possibleNewState.foreach { newState =>
        instanceMap += instanceId -> newState
        // In case we don't receive an update a TaskOpRejected message with TASK_OP_REJECTED_TIMEOUT_REASON
        // reason is scheduled within config.taskOpNotificationTimeout milliseconds. This will trigger another
        // attempt to launch the task.
        //
        // NOTE: this can lead to a race condition where an additional task is launched: in a nutshell if a TaskOp A
        // is rejected due to an internal timeout, TaskLauncherActor will schedule another TaskOp.Launch B, because
        // it's under the impression that A has not succeeded/got lost. If the timeout is triggered internally, the
        // tasksToLaunch count will be increased by 1. The TaskOp A can still be accepted though, and if it is,
        // two tasks (A and B) might be launched instead of one (given Marathon receives sufficient offers and is
        // able to create another TaskOp).
        // This would lead to the app being over-provisioned (e.g. "3 of 2 tasks" launched) but eventually converge
        // to the target task count when tasks over capacity are killed. With a sufficiently high timeout this case
        // should be fairly rare.
        // A better solution would involve an overhaul of the way TaskLaunchActor works and might be
        // a subject to change in the future.
        scheduleTaskOpTimeout(instanceOp)
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()
    }

    updateActorState()

    logger.debug(s"Request ${instanceOp.getClass.getSimpleName} for instance '${instanceOp.instanceId.idString}', version '${runSpec.version}'. $status")
    promise.trySuccess(MatchedInstanceOps(offer.getId, Seq(InstanceOpWithSource(myselfAsLaunchSource, instanceOp))))
  }

  private[this] def scheduleTaskOpTimeout(instanceOp: InstanceOp): Unit = {
    val reject = InstanceOpSourceDelegate.InstanceOpRejected(
      instanceOp, TaskLauncherActor.OfferOperationRejectedTimeoutReason
    )
    val cancellable = scheduleTaskOperationTimeout(context, reject)
    inFlightInstanceOperations += instanceOp.instanceId -> cancellable
  }

  private[this] def inFlight(op: InstanceOp): Boolean = inFlightInstanceOperations.contains(op.instanceId)

  protected def scheduleTaskOperationTimeout(
    context: ActorContext,
    message: InstanceOpSourceDelegate.InstanceOpRejected): Cancellable =
    {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, message)
    }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchInstances: Boolean = instancesToLaunch > 0 && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(until) if until > clock.now() => s"currently waiting for backoff($until)"
      case _ => "not backing off"
    }

    val inFlight = inFlightInstanceOperations.size
    val launchedOrRunning = instanceMap.values.count(_.isLaunched) - inFlight
    val instanceCountDelta = instanceMap.size + instancesToLaunch - runSpec.instances
    val matchInstanceStr = if (instanceCountDelta == 0) "" else s"instance count delta $instanceCountDelta."
    s"$instancesToLaunch instancesToLaunch, $inFlight in flight, " +
      s"$launchedOrRunning confirmed. $matchInstanceStr $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if instancesToLaunch > 0. */
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = {
      //set the precedence only, if this app is resident
      new ActorOfferMatcher(self, runSpec.residency.map(_ => runSpec.id))(context.system.scheduler)
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
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
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
