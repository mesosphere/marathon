package mesosphere.marathon.core.launchqueue.impl

import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceCreated, InstanceDeleted, InstanceUpdated }
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory }
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.InstanceOpNotification
import mesosphere.marathon.core.matcher.base.util.{ ActorOfferMatcher, InstanceOpSourceDelegate }
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.TaskStateChange
import mesosphere.marathon.state.{ RunSpec, Timestamp }
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.immutable
import scala.concurrent.duration._

private[launchqueue] object TaskLauncherActor {
  // scalastyle:off parameter.number
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskOpFactory: InstanceOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef)(
    runSpec: RunSpec,
    initialCount: Int): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      instanceTracker, rateLimiterActor,
      runSpec, initialCount))
  }
  // scalastyle:on parameter.number

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
// scalastyle:off parameter.number
private class TaskLauncherActor(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    instanceOpFactory: InstanceOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    rateLimiterActor: ActorRef,

    private[this] var runSpec: RunSpec,
    private[this] var instancesToLaunch: Int) extends Actor with ActorLogging with Stash {
  // scalastyle:on parameter.number

  private[this] var inFlightInstanceOperations = Map.empty[Instance.Id, Cancellable]

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  /** instances that are in flight and those in the tracker */
  private[this] var instanceMap: Map[Instance.Id, Instance] = _

  /** Decorator to use this actor as a [[OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = InstanceOpSourceDelegate(self)

  override def preStart(): Unit = {
    super.preStart()

    log.info(
      "Started instanceLaunchActor for {} version {} with initial count {}",
      runSpec.id, runSpec.version, instancesToLaunch)

    instanceMap = instanceTracker.instancesBySpecSync.instancesMap(runSpec.id).instanceMap
    rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightInstanceOperations.nonEmpty) {
      log.warning("Actor shutdown while instances are in flight: {}", inFlightInstanceOperations.keys.mkString(", "))
      inFlightInstanceOperations.values.foreach(_.cancel())
    }

    super.postStop()

    log.info("Stopped InstanceLauncherActor for {} version {}", runSpec.id, runSpec.version)
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(spec, delayUntil) if spec == runSpec =>
      stash()
      unstashAll()
      context.become(active)
    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      log.warning("Received delay update for other runSpec: {}", msg)
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveTaskUpdate,
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
        log.info("schedule timeout for stopping in " + config.taskOpNotificationTimeout().milliseconds)
        context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, PoisonPill)
      }
      waitForInFlightIfNecessary()
  }

  private[this] def waitForInFlightIfNecessary(): Unit = {
    if (inFlightInstanceOperations.isEmpty) {
      context.stop(self)
    } else {
      val taskIds = inFlightInstanceOperations.keys.take(3).mkString(", ")
      log.info(
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

      if (backOffUntil != Some(delayUntil)) {

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

      log.debug("After delay update {}", status)

    case msg @ RateLimiterActor.DelayUpdate(spec, delayUntil) if spec != runSpec =>
      log.warning("Received delay update for other runSpec: {}", msg)

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) if inFlight(op) =>
      removeInstance(op.instanceId)
      log.info(
        "Task op '{}' for {} was REJECTED, reason '{}', rescheduling. {}",
        op.getClass.getSimpleName, op.instanceId, reason, status)

      op match {
        // only increment for launch ops, not for reservations:
        case _: InstanceOp.LaunchTask => instancesToLaunch += 1
        // TODO(PODS): case InstanceOp.LaunchTaskGroup => ...
        case _ => ()
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()

    case InstanceOpSourceDelegate.InstanceOpRejected(op, TaskLauncherActor.OfferOperationRejectedTimeoutReason) =>
      // This is a message that we scheduled in this actor.
      // When we receive a launch confirmation or rejection, we cancel this timer but
      // there is still a race and we might send ourselves the message nevertheless, so we just
      // ignore it here.
      log.debug("Ignoring task launch rejected for '{}' as the task is not in flight anymore", op.instanceId)

    case InstanceOpSourceDelegate.InstanceOpRejected(op, reason) =>
      log.warning("Unexpected task op '{}' rejected for {}.", op.getClass.getSimpleName, op.instanceId)

    case InstanceOpSourceDelegate.InstanceOpAccepted(op) =>
      inFlightInstanceOperations -= op.instanceId
      log.info("Task op '{}' for {} was accepted. {}", op.getClass.getSimpleName, op.instanceId, status)
  }

  private[this] def receiveTaskUpdate: Receive = {
    case TaskChanged(stateOp, stateChange) =>
      stateChange match {
        case TaskStateChange.Update(newState, _) =>
          log.info("receiveTaskUpdate: updating status of {}", newState.taskId)
          instanceMap += newState.taskId.instanceId -> Instance(newState)

        case TaskStateChange.Expunge(task) =>
          log.info("receiveTaskUpdate: {} finished", task.taskId)
          removeInstance(task.taskId.instanceId)
          // A) If the app has constraints, we need to reconsider offers that
          // we already rejected. E.g. when a host:unique constraint prevented
          // us to launch tasks on a particular node before, we need to reconsider offers
          // of that node after a task on that node has died.
          //
          // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
          // TODO (pods): we don't want to handle something like isResident here
          if (runSpec.constraints.nonEmpty || (runSpec.residency.isDefined && shouldLaunchInstances)) {
            maybeOfferReviver.foreach(_.reviveOffers())
          }

        case _ =>
          log.info("receiveTaskUpdate: ignoring stateChange {}", stateChange)
      }
      replyWithQueuedInstanceCount()
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case change: InstanceChange =>
      change match {
        case update: InstanceCreated =>
          log.info("receiveInstanceUpdate: {} is {}", update.id, status)
          instanceMap += update.id -> update.instance

        case update: InstanceUpdated =>
          log.info("receiveInstanceUpdate: {} is {}", update.id, status)
          instanceMap += update.id -> update.instance

        case update: InstanceDeleted =>
          log.info("receiveInstanceUpdate: {} was deleted ({})", update.id, status)
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
      replyWithQueuedInstanceCount()
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
          log.info(
            "getting new runSpec for '{}', version {} with {} initial instances",
            runSpec.id, runSpec.version, addCount
          )

          suspendMatchingUntilWeGetBackoffDelayUpdate()

        } else {
          log.info(
            "scaling change for '{}', version {} with {} initial instances",
            runSpec.id, runSpec.version, addCount
          )
        }
      } else {
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
      unreachableInstances = instanceMap.values.count(instance => instance.isUnreachable),
      backOffUntil.getOrElse(clock.now())
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer) if clock.now() >= deadline || !shouldLaunchInstances =>
      val deadlineReached = clock.now() >= deadline
      log.debug("ignoring offer, offer deadline {}reached. {}", if (deadlineReached) "" else "NOT ", status)
      sender ! MatchedInstanceOps(offer.getId)

    case ActorOfferMatcher.MatchOffer(deadline, offer) =>
      val matchRequest = InstanceOpFactory.Request(runSpec, offer, instanceMap, instancesToLaunch)
      val instanceOp: Option[InstanceOp] = instanceOpFactory.buildTaskOp(matchRequest)
      instanceOp match {
        case Some(op) => handleInstanceOp(op, offer)
        case None => sender() ! MatchedInstanceOps(offer.getId)
      }
  }

  private[this] def handleInstanceOp(instanceOp: InstanceOp, offer: Mesos.Offer): Unit = {
    def updateActorState(): Unit = {
      val instanceId = instanceOp.instanceId
      instanceOp match {
        // only decrement for launched instances, not for reservations:
        case _: InstanceOp.LaunchTask => instancesToLaunch -= 1
        // TODO(PODS): case InstanceOp.LaunchTaskGroup => ...
        case _ => ()
      }

      // We will receive the updated instance once it's been persisted. Before that,
      // we can only store the possible state, as we don't have the updated state
      // yet.
      instanceOp.stateOp.possibleNewState.foreach { newState =>
        instanceMap += instanceId -> newState
        scheduleTaskOpTimeout(instanceOp)
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()
    }

    log.info(
      "Request {} for instance '{}', version '{}'. {}",
      instanceOp.getClass.getSimpleName, instanceOp.instanceId.idString, runSpec.version, status)

    updateActorState()
    sender() ! MatchedInstanceOps(offer.getId, immutable.Seq(InstanceOpWithSource(myselfAsLaunchSource, instanceOp)))
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
      new ActorOfferMatcher(clock, self, runSpec.residency.map(_ => runSpec.id))
    }
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchInstances

      if (shouldBeRegistered && !registeredAsMatcher) {
        log.debug("Registering for {}, {}.", runSpec.id, runSpec.version)
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      } else if (!shouldBeRegistered && registeredAsMatcher) {
        if (instancesToLaunch > 0) {
          log.info("Backing off due to task failures. Stop receiving offers for {}, {}", runSpec.id, runSpec.version)
        } else {
          log.info("No tasks left to launch. Stop receiving offers for {}, {}", runSpec.id, runSpec.version)
        }
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }

    def unregister(): Unit = {
      if (registeredAsMatcher) {
        log.info("Deregister as matcher.")
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }
  }
}
