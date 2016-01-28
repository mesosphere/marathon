package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ Actor, ActorContext, ActorLogging, ActorRef, Cancellable, Props, Stash }
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.AppTaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ TaskOp, MatchedTaskOps, TaskOpWithSource }
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.TaskLaunchNotification
import mesosphere.marathon.core.matcher.base.util.{ ActorOfferMatcher, TaskOpSourceDelegate }
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.marathon.tasks.TaskFactory
import org.apache.mesos.Protos.{ TaskID, TaskInfo }

import scala.concurrent.duration._

private[launchqueue] object AppTaskLauncherActor {
  // scalastyle:off parameter.number
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskFactory: TaskFactory,
    maybeOfferReviver: Option[OfferReviver],
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef)(
      app: AppDefinition,
      initialCount: Int): Props = {
    Props(new AppTaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskFactory,
      maybeOfferReviver,
      taskTracker, rateLimiterActor,
      app, initialCount))
  }
  // scalastyle:on parameter.number

  sealed trait Requests

  /**
    * Increase the task count of the receiver.
    * The actor responds with a [[QueuedTaskCount]] message.
    */
  case class AddTasks(app: AppDefinition, count: Int) extends Requests
  /**
    * Get the current count.
    * The actor responds with a [[QueuedTaskCount]] message.
    */
  case object GetCount extends Requests

  /**
    * Results in rechecking whether we may launch tasks.
    */
  private case object RecheckIfBackOffUntilReached extends Requests

  private case class ScheduleTaskLaunchNotificationTimeout(launch: OfferMatcher.Launch)

  case object Stop extends Requests

  private val TASK_LAUNCH_REJECTED_TIMEOUT_REASON: String =
    "AppTaskLauncherActor: no accept received within timeout. " +
      "You can reconfigure the timeout with --task_launch_notification_timeout."
}

/**
  * Allows processing offers for starting tasks for the given app.
  */
// scalastyle:off parameter.number
private class AppTaskLauncherActor(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskFactory: TaskFactory,
    maybeOfferReviver: Option[OfferReviver],
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef,

    private[this] var app: AppDefinition,
    private[this] var tasksToLaunch: Int) extends Actor with ActorLogging with Stash {
  // scalastyle:on parameter.number

  private[this] var inFlightTaskLaunches = Map.empty[String, Option[Cancellable]]

  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  /** tasks that are in flight and those in the tracker */
  private[this] var tasksMap: Map[String, MarathonTask] = _

  /** Decorator to use this actor as a [[base.OfferMatcher#TaskOpSource]] */
  private[this] val myselfAsLaunchSource = TaskOpSourceDelegate(self)

  override def preStart(): Unit = {
    super.preStart()

    log.info("Started appTaskLaunchActor for {} version {} with initial count {}",
      app.id, app.version, tasksToLaunch)

    val runningTasks = taskTracker.appTasksSync(app.id)
    tasksMap = runningTasks.map(task => task.getId -> task).toMap

    rateLimiterActor ! RateLimiterActor.GetDelay(app)
  }

  override def postStop(): Unit = {
    OfferMatcherRegistration.unregister()
    recheckBackOff.foreach(_.cancel())

    if (inFlightTaskLaunches.nonEmpty) {
      log.warning("Actor shutdown but still some tasks in flight: {}", inFlightTaskLaunches.keys.mkString(", "))
      inFlightTaskLaunches.values.foreach(_.foreach(_.cancel()))
    }

    super.postStop()

    log.info("Stopped appTaskLaunchActor for {} version {}", app.id, app.version)
  }

  override def receive: Receive = waitForInitialDelay

  private[this] def waitForInitialDelay: Receive = LoggingReceive.withLabel("waitingForInitialDelay") {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == app =>
      stash()
      unstashAll()
      context.become(active)
    case msg @ RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp != app =>
      log.warning("Received delay update for other app: {}", msg)
    case message: Any => stash()
  }

  private[this] def active: Receive = LoggingReceive.withLabel("active") {
    Seq(
      receiveStop,
      receiveDelayUpdate,
      receiveTaskLaunchNotification,
      receiveTaskStatusUpdate,
      receiveGetCurrentCount,
      receiveAddCount,
      receiveProcessOffers,
      receiveScheduleTaskLaunchNotificationDelay
    ).reduce(_.orElse[Any, Unit](_))
  }

  private[this] def receiveWaitingForInFlight: Receive = LoggingReceive.withLabel("waitingForInFlight") {
    case notification: TaskLaunchNotification =>
      receiveTaskLaunchNotification(notification)
      waitingForInFlight()

    case "waitingForInFlight" => sender() ! "waitingForInFlight" // for testing
  }

  private[this] def receiveStop: Receive = {
    case AppTaskLauncherActor.Stop => waitingForInFlight()
  }

  private[this] def waitingForInFlight(): Unit = {
    if (inFlightTaskLaunches.isEmpty) {
      context.stop(self)
    }
    else {
      context.become(receiveWaitingForInFlight)
    }
  }

  /**
    * Receive rate limiter updates.
    */
  private[this] def receiveDelayUpdate: Receive = {
    case RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp == app =>

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

    case msg @ RateLimiterActor.DelayUpdate(delayApp, delayUntil) if delayApp != app =>
      log.warning("Received delay update for other app: {}", msg)

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case TaskOpSourceDelegate.TaskLaunchRejected(op, reason) if inFlight(op) =>
      removeTask(op.taskId)
      tasksToLaunch += 1
      log.info("Task launch for '{}' was REJECTED, reason '{}', rescheduling. {}", op.taskId, reason, status)
      OfferMatcherRegistration.manageOfferMatcherStatus()

    case TaskOpSourceDelegate.TaskLaunchRejected(
      op, AppTaskLauncherActor.TASK_LAUNCH_REJECTED_TIMEOUT_REASON
      ) =>

      log.debug("Unnecessary timeout message. Ignoring task launch rejected for task id '{}'.", op.taskId)

    case TaskOpSourceDelegate.TaskLaunchRejected(op, reason) =>
      log.warning("Unexpected task launch rejected for taskId '{}'.", op.taskId)

    case TaskOpSourceDelegate.TaskLaunchAccepted(op) =>
      inFlightTaskLaunches -= op.taskId
      log.info("Task launch for '{}' was accepted. {}", op.taskId, status)
  }

  private[this] def receiveTaskStatusUpdate: Receive = {
    case TaskStatusUpdate(_, taskId, MarathonTaskStatus.Terminal(_)) =>
      log.debug("task '{}' finished", taskId.getValue)
      removeTask(taskId.getValue)

      // If the app has constraints, we need to reconsider offers that
      // we already rejected. E.g. when a host:unique constraint prevented
      // us to launch tasks on a particular node before, we need to reconsider offers
      // of that node after a task on that node has died.
      if (app.constraints.nonEmpty) {
        maybeOfferReviver.foreach(_.reviveOffers())
      }

      replyWithQueuedTaskCount()

    case TaskStatusUpdate(_, taskId, status) =>
      tasksMap.get(taskId.getValue) match {
        case None =>
          log.warning("ignore update of unknown task '{}'", taskId.getValue)
        case Some(task) =>
          log.debug("updating status of task '{}'", taskId.getValue)

          val taskBuilder = task.toBuilder
          status.mesosStatus.foreach(taskBuilder.setStatus)
          val updatedTask = taskBuilder.build()

          tasksMap += taskId.getValue -> updatedTask
      }

      replyWithQueuedTaskCount()
  }

  private[this] def removeTask(taskId: String): Unit = {
    inFlightTaskLaunches.get(taskId).foreach(_.foreach(_.cancel()))
    inFlightTaskLaunches -= taskId
    tasksMap -= taskId
  }

  private[this] def receiveGetCurrentCount: Receive = {
    case AppTaskLauncherActor.GetCount =>
      replyWithQueuedTaskCount()
  }

  private[this] def receiveAddCount: Receive = {
    case AppTaskLauncherActor.AddTasks(newApp, addCount) =>
      val configChange = app.isUpgrade(newApp)
      if (configChange || app.needsRestart(newApp) || app.isOnlyScaleChange(newApp)) {
        app = newApp
        tasksToLaunch = addCount

        if (configChange) {
          log.info(
            "getting new app definition config for '{}', version {} with {} initial tasks",
            app.id, app.version, addCount
          )

          suspendMatchingUntilWeGetBackoffDelayUpdate()

        }
        else {
          log.info(
            "scaling change for '{}', version {} with {} initial tasks",
            app.id, app.version, addCount
          )
        }
      }
      else {
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
    rateLimiterActor ! RateLimiterActor.GetDelay(app)
    context.become(waitForInitialDelay)
  }

  private[this] def replyWithQueuedTaskCount(): Unit = {
    sender() ! QueuedTaskCount(
      app,
      tasksLeftToLaunch = tasksToLaunch,
      taskLaunchesInFlight = inFlightTaskLaunches.size,
      tasksLaunchedOrRunning = tasksMap.size - inFlightTaskLaunches.size,
      backOffUntil.getOrElse(clock.now())
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer) if clock.now() >= deadline || !shouldLaunchTasks =>
      val deadlineReached = clock.now() >= deadline
      log.debug("ignoring offer, offer deadline {}reached. {}", if (deadlineReached) "" else "NOT ", status)
      sender ! MatchedTaskOps(offer.getId, Seq.empty)

    case ActorOfferMatcher.MatchOffer(deadline, offer) =>
      val newTaskOpt: Option[CreatedTask] = taskFactory.newTask(app, offer, tasksMap.values)
      newTaskOpt match {
        case Some(CreatedTask(mesosTask, marathonTask)) =>
          def updateActorState(): Unit = {
            tasksMap += marathonTask.getId -> marathonTask
            inFlightTaskLaunches += mesosTask.getTaskId.getValue -> None
            tasksToLaunch -= 1
            OfferMatcherRegistration.manageOfferMatcherStatus()
          }

          updateActorState()

          log.info("Request to launch task with id '{}', version '{}'. {}",
            mesosTask.getTaskId.getValue, app.version, status)

          val launch = OfferMatcher.Launch(mesosTask, marathonTask)
          self ! AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout(launch)
          sender() ! MatchedTaskOps(offer.getId, Seq(TaskOpWithSource(myselfAsLaunchSource, launch)))

        case None => sender() ! MatchedTaskOps(offer.getId, Seq.empty)
      }
  }

  protected val receiveScheduleTaskLaunchNotificationDelay: Receive = {
    case AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout(launch) if inFlight(launch) =>
      val reject = TaskOpSourceDelegate.TaskLaunchRejected(
        launch, AppTaskLauncherActor.TASK_LAUNCH_REJECTED_TIMEOUT_REASON
      )
      val cancellable = scheduleTaskLaunchTimeout(context, reject)
      inFlightTaskLaunches += launch.taskId -> Some(cancellable)

    case schedule: AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout =>
      log.debug("ignoring {}", schedule)
  }

  private[this] def inFlight(task: TaskOp): Boolean = inFlightTaskLaunches.contains(task.taskId)
  private[this] def inFlight(taskId: String): Boolean = inFlightTaskLaunches.contains(taskId)

  protected def scheduleTaskLaunchTimeout(
    context: ActorContext,
    message: TaskOpSourceDelegate.TaskLaunchRejected): Cancellable =
    {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(config.taskLaunchNotificationTimeout().milliseconds, self, message)
    }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchTasks: Boolean = tasksToLaunch > 0 && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(until) if until > clock.now() => s"currently waiting for backoff($until)"
      case _                                  => "not backing off"
    }

    val inFlight = inFlightTaskLaunches.size
    val tasksLaunchedOrRunning = tasksMap.size - inFlight
    val instanceCountDelta = tasksMap.size + tasksToLaunch - app.instances
    val matchInstanceStr = if (instanceCountDelta == 0) "" else s"instance count delta $instanceCountDelta."
    s"$tasksToLaunch tasksToLaunch, $inFlight in flight, " +
      s"$tasksLaunchedOrRunning confirmed. $matchInstanceStr $backoffStr"
  }

  /** Manage registering this actor as offer matcher. Only register it if tasksToLaunch > 0. */
  private[this] object OfferMatcherRegistration {
    private[this] val myselfAsOfferMatcher: OfferMatcher = new ActorOfferMatcher(clock, self)
    private[this] var registeredAsMatcher = false

    /** Register/unregister as necessary */
    def manageOfferMatcherStatus(): Unit = {
      val shouldBeRegistered = shouldLaunchTasks

      if (shouldBeRegistered && !registeredAsMatcher) {
        log.debug("Registering for {}, {}.", app.id, app.version)
        offerMatcherManager.addSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = true
      }
      else if (!shouldBeRegistered && registeredAsMatcher) {
        if (tasksToLaunch > 0) {
          log.info("Backing off due to task failures. Stop receiving offers for {}, {}", app.id, app.version)
        }
        else {
          log.info("No tasks left to launch. Stop receiving offers for {}, {}", app.id, app.version)
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
