package mesosphere.marathon.core.launchqueue.impl

import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.TaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpWithSource }
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.TaskOpNotification
import mesosphere.marathon.core.matcher.base.util.{ ActorOfferMatcher, TaskOpSourceDelegate }
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.{ Task, TaskStateChange }
import mesosphere.marathon.state.{ RunSpec, Timestamp }
import org.apache.mesos.{ Protos => Mesos }

import scala.concurrent.duration._

private[launchqueue] object TaskLauncherActor {
  // scalastyle:off parameter.number
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskOpFactory: TaskOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef)(
    runSpec: RunSpec,
    initialCount: Int): Props = {
    Props(new TaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskOpFactory,
      maybeOfferReviver,
      taskTracker, rateLimiterActor,
      runSpec, initialCount))
  }
  // scalastyle:on parameter.number

  sealed trait Requests

  /**
    * Increase the task count of the receiver.
    * The actor responds with a [[QueuedTaskInfo]] message.
    */
  case class AddTasks(runSpec: RunSpec, count: Int) extends Requests
  /**
    * Get the current count.
    * The actor responds with a [[QueuedTaskInfo]] message.
    */
  case object GetCount extends Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  case object Stop extends Requests

  private val TASK_OP_REJECTED_TIMEOUT_REASON: String =
    "AppTaskLauncherActor: no accept received within timeout. " +
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
    taskOpFactory: TaskOpFactory,
    maybeOfferReviver: Option[OfferReviver],
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef,

    private[this] var runSpec: RunSpec,
    private[this] var tasksToLaunch: Int) extends Actor with ActorLogging with Stash {
  // scalastyle:on parameter.number

  private[this] var inFlightTaskOperations = Map.empty[Task.Id, Cancellable]

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  /** tasks that are in flight and those in the tracker */
  private[this] var tasksMap: Map[Task.Id, Task] = _

  /** Decorator to use this actor as a [[base.OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = TaskOpSourceDelegate(self)

  override def preStart(): Unit = {
    super.preStart()

    log.info(
      "Started taskLaunchActor for {} version {} with initial count {}",
      runSpec.id, runSpec.version, tasksToLaunch)

    tasksMap = taskTracker.tasksByAppSync.appTasksMap(runSpec.id).taskMap
    log.info(">>> received tasksMap: {}", tasksMap.keySet.mkString(","))

    rateLimiterActor ! RateLimiterActor.GetDelay(runSpec)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightTaskOperations.nonEmpty) {
      log.warning("Actor shutdown but still some tasks in flight: {}", inFlightTaskOperations.keys.mkString(", "))
      inFlightTaskOperations.values.foreach(_.cancel())
    }

    super.postStop()

    log.info("Stopped appTaskLaunchActor for {} version {}", runSpec.id, runSpec.version)
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == runSpec =>
      stash()
      unstashAll()
      context.become(active)
    case msg @ RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp != runSpec =>
      log.warning("Received delay update for other app: {}", msg)
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveTaskUpdate,
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
    case notification: TaskOpNotification =>
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
      if (inFlightTaskOperations.nonEmpty) {
        // try to stop gracefully but also schedule timeout
        import context.dispatcher
        log.info("schedule timeout for stopping in " + config.taskOpNotificationTimeout().milliseconds)
        context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, PoisonPill)
      }
      waitForInFlightIfNecessary()
  }

  private[this] def waitForInFlightIfNecessary(): Unit = {
    if (inFlightTaskOperations.isEmpty) {
      context.stop(self)
    } else {
      val taskIds = inFlightTaskOperations.keys.take(3).mkString(", ")
      log.info(
        s"Stopping but still waiting for ${inFlightTaskOperations.size} in-flight messages, " +
          s"first three task ids: $taskIds"
      )
      context.become(stopping)
    }
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == runSpec =>

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

    case msg @ RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp != runSpec =>
      log.warning("Received delay update for other app: {}", msg)

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case TaskOpSourceDelegate.TaskOpRejected(op, reason) if inFlight(op) =>
      removeTask(op.taskId)
      log.info(
        "Task op '{}' for {} was REJECTED, reason '{}', rescheduling. {}",
        op.getClass.getSimpleName, op.taskId, reason, status)

      op match {
        // only increment for launch ops, not for reservations:
        case _: TaskOp.Launch => tasksToLaunch += 1
        case _ => ()
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()

    case TaskOpSourceDelegate.TaskOpRejected(op, TaskLauncherActor.TASK_OP_REJECTED_TIMEOUT_REASON) =>
      // This is a message that we scheduled in this actor.
      // When we receive a launch confirmation or rejection, we cancel this timer but
      // there is still a race and we might send ourselves the message nevertheless, so we just
      // ignore it here.
      log.debug("Ignoring task launch rejected for '{}' as the task is not in flight anymore", op.taskId)

    case TaskOpSourceDelegate.TaskOpRejected(op, reason) =>
      log.warning("Unexpected task op '{}' rejected for {}.", op.getClass.getSimpleName, op.taskId)

    case TaskOpSourceDelegate.TaskOpAccepted(op) =>
      inFlightTaskOperations -= op.taskId
      log.info("Task op '{}' for {} was accepted. {}", op.getClass.getSimpleName, op.taskId, status)
  }

  private[this] def receiveTaskUpdate: Receive = {
    case TaskChanged(stateOp, stateChange) =>
      stateChange match {
        case TaskStateChange.Update(newState, _) =>
          log.info("receiveTaskUpdate: updating status of {}", newState.taskId)
          tasksMap += newState.taskId -> newState

        case TaskStateChange.Expunge(task) =>
          log.info("receiveTaskUpdate: {} finished", task.taskId)
          removeTask(task.taskId)
          // A) If the app has constraints, we need to reconsider offers that
          // we already rejected. E.g. when a host:unique constraint prevented
          // us to launch tasks on a particular node before, we need to reconsider offers
          // of that node after a task on that node has died.
          //
          // B) If a reservation timed out, already rejected offers might become eligible for creating new reservations.
          if (runSpec.constraints.nonEmpty || (runSpec.isResident && shouldLaunchTasks)) {
            maybeOfferReviver.foreach(_.reviveOffers())
          }

        case _ =>
          log.info("receiveTaskUpdate: ignoring stateChange {}", stateChange)
      }
      replyWithQueuedTaskCount()
  }

  private[this] def removeTask(taskId: Task.Id): Unit = {
    inFlightTaskOperations.get(taskId).foreach(_.cancel())
    inFlightTaskOperations -= taskId
    tasksMap -= taskId
  }

  private[this] def receiveGetCurrentCount: Receive = {
    case TaskLauncherActor.GetCount =>
      replyWithQueuedTaskCount()
  }

  private[this] def receiveAddCount: Receive = {
    case TaskLauncherActor.AddTasks(newApp, addCount) =>
      val configChange = runSpec.isUpgrade(newApp)
      if (configChange || runSpec.needsRestart(newApp) || runSpec.isOnlyScaleChange(newApp)) {
        runSpec = newApp
        tasksToLaunch = addCount

        if (configChange) {
          log.info(
            "getting new app definition config for '{}', version {} with {} initial tasks",
            runSpec.id, runSpec.version, addCount
          )

          suspendMatchingUntilWeGetBackoffDelayUpdate()

        } else {
          log.info(
            "scaling change for '{}', version {} with {} initial tasks",
            runSpec.id, runSpec.version, addCount
          )
        }
      } else {
        tasksToLaunch += addCount
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()

      replyWithQueuedTaskCount()
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

  private[this] def replyWithQueuedTaskCount(): Unit = {
    val tasksLaunched = tasksMap.values.count(_.launched.isDefined)
    val taskLaunchesInFlight = inFlightTaskOperations.keys
      .count(taskId => tasksMap.get(taskId).exists(_.launched.isDefined))
    sender() ! QueuedTaskInfo(
      runSpec,
      inProgress = tasksToLaunch > 0 || inFlightTaskOperations.nonEmpty,
      tasksLeftToLaunch = tasksToLaunch,
      finalTaskCount = tasksToLaunch + taskLaunchesInFlight + tasksLaunched,
      // TODO ju replaceable with MarathonTaskState ?
      tasksLost = tasksMap.values.count(_.mesosStatus.fold(false)(_.getState == Mesos.TaskState.TASK_LOST)),
      backOffUntil.getOrElse(clock.now())
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer) if clock.now() >= deadline || !shouldLaunchTasks =>
      val deadlineReached = clock.now() >= deadline
      log.debug("ignoring offer, offer deadline {}reached. {}", if (deadlineReached) "" else "NOT ", status)
      sender ! MatchedTaskOps(offer.getId, Seq.empty)

    case ActorOfferMatcher.MatchOffer(deadline, offer) =>
      val matchRequest = TaskOpFactory.Request(runSpec, offer, tasksMap, tasksToLaunch)
      val taskOp: Option[TaskOp] = taskOpFactory.buildTaskOp(matchRequest)
      taskOp match {
        case Some(op) => handleTaskOp(op, offer)
        case None => sender() ! MatchedTaskOps(offer.getId, Seq.empty)
      }
  }

  private[this] def handleTaskOp(taskOp: TaskOp, offer: Mesos.Offer): Unit = {
    def updateActorState(): Unit = {
      val taskId = taskOp.taskId
      taskOp match {
        // only decrement for launched tasks, not for reservations:
        case _: TaskOp.Launch => tasksToLaunch -= 1
        case _ => ()
      }

      // We will receive the updated task once it's been persisted. Before that,
      // we can only store the possible state, as we don't have the updated task
      // yet.
      taskOp.stateOp.possibleNewState.foreach { newState =>
        tasksMap += taskId -> newState
        scheduleTaskOpTimeout(taskOp)
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()
    }

    log.info(
      "Request {} for task '{}', version '{}'. {}",
      taskOp.getClass.getSimpleName, taskOp.taskId.idString, runSpec.version, status)

    updateActorState()
    sender() ! MatchedTaskOps(offer.getId, Seq(TaskOpWithSource(myselfAsLaunchSource, taskOp)))
  }

  private[this] def scheduleTaskOpTimeout(taskOp: TaskOp): Unit = {
    val reject = TaskOpSourceDelegate.TaskOpRejected(
      taskOp, TaskLauncherActor.TASK_OP_REJECTED_TIMEOUT_REASON
    )
    val cancellable = scheduleTaskOperationTimeout(context, reject)
    inFlightTaskOperations += taskOp.taskId -> cancellable
  }

  private[this] def inFlight(task: TaskOp): Boolean = inFlightTaskOperations.contains(task.taskId)

  protected def scheduleTaskOperationTimeout(
    context: ActorContext,
    message: TaskOpSourceDelegate.TaskOpRejected): Cancellable =
    {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(config.taskOpNotificationTimeout().milliseconds, self, message)
    }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchTasks: Boolean = tasksToLaunch > 0 && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(until) if until > clock.now() => s"currently waiting for backoff($until)"
      case _ => "not backing off"
    }

    val inFlight = inFlightTaskOperations.size
    val tasksLaunchedOrRunning = tasksMap.values.count(_.launched.isDefined) - inFlight
    val instanceCountDelta = tasksMap.size + tasksToLaunch - runSpec.instances
    val matchInstanceStr = if (instanceCountDelta == 0) "" else s"instance count delta $instanceCountDelta."
    s"$tasksToLaunch tasksToLaunch, $inFlight in flight, " +
      s"$tasksLaunchedOrRunning confirmed. $matchInstanceStr $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if tasksToLaunch > 0. */
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = {
      //set the precedence only, if this app is resident
      new ActorOfferMatcher(clock, self, runSpec.residency.map(_ => runSpec.id))
    }
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchTasks

      if (shouldBeRegistered && !registeredAsMatcher) {
        log.debug("Registering for {}, {}.", runSpec.id, runSpec.version)
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      } else if (!shouldBeRegistered && registeredAsMatcher) {
        if (tasksToLaunch > 0) {
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
