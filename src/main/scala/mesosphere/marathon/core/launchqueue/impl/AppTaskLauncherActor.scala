package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorContext, Stash, Cancellable, Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.AppTaskLauncherActor.RecheckIfBackOffUntilReached
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.{ MatchedTasks, TaskWithSource }
import mesosphere.marathon.core.matcher.base
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.matcher.base.util.TaskLaunchSourceDelegate.TaskLaunchNotification
import mesosphere.marathon.core.matcher.base.util.{ TaskLaunchSourceDelegate, ActorOfferMatcher }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusObservables }
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }
import org.apache.mesos.Protos.{ TaskInfo, TaskID }
import rx.lang.scala.Subscription

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.pattern.pipe

import scala.util.control.NonFatal

private[launchqueue] object AppTaskLauncherActor {
  // scalastyle:off parameter.number
  def props(
    config: LaunchQueueConfig,
    offerMatcherManager: OfferMatcherManager,
    clock: Clock,
    taskFactory: TaskFactory,
    taskStatusObservable: TaskStatusObservables,
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef)(
      app: AppDefinition,
      initialCount: Int): Props = {
    Props(new AppTaskLauncherActor(
      config,
      offerMatcherManager,
      clock, taskFactory, taskStatusObservable, taskTracker, rateLimiterActor,
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

  private case class ScheduleTaskLaunchNotificationTimeout(task: TaskInfo)

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
    taskStatusObservable: TaskStatusObservables,
    taskTracker: TaskTracker,
    rateLimiterActor: ActorRef,

    private[this] var app: AppDefinition,
    private[this] var tasksToLaunch: Int) extends Actor with ActorLogging with Stash {
  // scalastyle:on parameter.number

  private[this] var inFlightTaskLaunches = Map.empty[TaskID, Option[Cancellable]]
  private[this] var recheckBackOff: Option[Cancellable] = None
  private[this] var backOffUntil: Option[Timestamp] = None

  /** Receive task updates to keep task list up-to-date. */
  private[this] var taskStatusUpdateSubscription: Subscription = _
  /** Currently known running tasks or tasks that we have requested to be launched. */
  private[this] var runningTasks: Set[MarathonTask] = _
  /** Like runningTasks but indexed by the taskId String */
  private[this] var runningTasksMap: Map[String, MarathonTask] = _

  /** Decorator to use this actor as a [[base.OfferMatcher#TaskLaunchSource]] */
  private[this] val myselfAsLaunchSource = TaskLaunchSourceDelegate(self)

  override def preStart(): Unit = {
    super.preStart()

    log.info("Started appTaskLaunchActor for {} version {} with initial count {}",
      app.id, app.version, tasksToLaunch)

    taskStatusUpdateSubscription = taskStatusObservable.forAppId(app.id).subscribe { update =>
      log.debug("update {}", update)
      self ! update
    }
    runningTasks = taskTracker.get(app.id)
    runningTasksMap = runningTasks.map(task => task.getId -> task).toMap

    rateLimiterActor ! RateLimiterActor.GetDelay(app)
  }

  override def postStop(): Unit = {
    taskStatusUpdateSubscription.unsubscribe()
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

    case RecheckIfBackOffUntilReached => OfferMatcherRegistration.manageOfferMatcherStatus()
  }

  private[this] def receiveTaskLaunchNotification: Receive = {
    case TaskLaunchSourceDelegate.TaskLaunchRejected(taskInfo, reason) if inFlight(taskInfo) =>
      // This task is not yet known to mesos, so there will be no event that removes
      // it automatically from the taskTracker.
      taskTracker.terminated(app.id, taskInfo.getTaskId.getValue)
      removeTask(taskInfo.getTaskId)
      tasksToLaunch += 1
      log.info(
        "Task launch for '{}' was denied, reason '{}', rescheduling. {}",
        taskInfo.getTaskId.getValue, reason, status)
      OfferMatcherRegistration.manageOfferMatcherStatus()

    case TaskLaunchSourceDelegate.TaskLaunchRejected(
      taskInfo, AppTaskLauncherActor.TASK_LAUNCH_REJECTED_TIMEOUT_REASON
      ) =>

      log.debug("Unnecessary timeout message. Ignoring task launch rejected for task id '{}'.",
        taskInfo.getTaskId.getValue)

    case TaskLaunchSourceDelegate.TaskLaunchRejected(taskInfo, reason) =>
      log.warning("Unexpected task launch rejected for taskId '{}'.", taskInfo.getTaskId.getValue)

    case TaskLaunchSourceDelegate.TaskLaunchAccepted(taskInfo) =>
      inFlightTaskLaunches -= taskInfo.getTaskId
      log.info("Task launch for '{}' was accepted. {}", taskInfo.getTaskId.getValue, status)
  }

  private[this] def receiveTaskStatusUpdate: Receive = {
    case TaskStatusUpdate(_, taskId, MarathonTaskStatus.Terminal(_)) =>
      log.debug("task '{}' finished", taskId.getValue)
      removeTask(taskId)

    case TaskStatusUpdate(_, taskId, status) =>
      runningTasksMap.get(taskId.getValue) match {
        case None =>
          log.warning("ignore update of unknown task '{}'", taskId.getValue)
        case Some(task) =>
          log.debug("updating status of task '{}'", taskId.getValue)

          val taskBuilder = task.toBuilder
          status.mesosStatus.foreach(taskBuilder.setStatus)
          val updatedTask = taskBuilder.build()

          runningTasks -= task
          runningTasks += updatedTask
          runningTasksMap += taskId.getValue -> updatedTask
      }

  }

  private[this] def removeTask(taskId: TaskID): Unit = {
    inFlightTaskLaunches.get(taskId).foreach(_.foreach(_.cancel()))
    inFlightTaskLaunches -= taskId
    runningTasksMap.get(taskId.getValue).foreach { marathonTask =>
      runningTasksMap -= taskId.getValue
      runningTasks -= marathonTask
    }
  }

  private[this] def receiveGetCurrentCount: Receive = {
    case AppTaskLauncherActor.GetCount =>
      replyWithQueuedTaskCount()
  }

  private[this] def receiveAddCount: Receive = {
    case AppTaskLauncherActor.AddTasks(newApp, addCount) =>
      if (app != newApp) {
        app = newApp
        log.info("getting new app definition for '{}', version {} with {} initial tasks", app.id, app.version, addCount)
        tasksToLaunch = addCount
      }
      else {
        tasksToLaunch += addCount
      }

      OfferMatcherRegistration.manageOfferMatcherStatus()

      replyWithQueuedTaskCount()
  }

  private[this] def replyWithQueuedTaskCount(): Unit = {
    sender() ! QueuedTaskCount(
      app,
      tasksLeftToLaunch = tasksToLaunch,
      taskLaunchesInFlight = inFlightTaskLaunches.size,
      tasksLaunchedOrRunning = runningTasks.size - inFlightTaskLaunches.size,
      backOffUntil.getOrElse(clock.now())
    )
  }

  private[this] def receiveProcessOffers: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer) if clock.now() >= deadline || !shouldLaunchTasks =>
      val deadlineReached = clock.now() >= deadline
      log.debug("ignoring offer, offer deadline {}reached. {}", if (deadlineReached) "" else "NOT ", status)
      sender ! MatchedTasks(offer.getId, Seq.empty)

    case ActorOfferMatcher.MatchOffer(deadline, offer) =>
      val newTaskOpt: Option[CreatedTask] = taskFactory.newTask(app, offer, runningTasks)
      newTaskOpt match {
        case Some(CreatedTask(mesosTask, marathonTask)) =>
          def updateActorState(): Unit = {
            runningTasks += marathonTask
            runningTasksMap += marathonTask.getId -> marathonTask
            inFlightTaskLaunches += mesosTask.getTaskId -> None
            tasksToLaunch -= 1
            OfferMatcherRegistration.manageOfferMatcherStatus()
          }
          def saveTask(): Future[Seq[TaskInfo]] = {
            taskTracker.created(app.id, marathonTask)
            val context = this.context
            import context.dispatcher
            taskTracker
              .store(app.id, marathonTask)
              .map { _ =>
                self ! AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout(mesosTask)
                Seq(mesosTask)
              }.recover {
                case NonFatal(e) =>
                  log.error(e, "While storing task '{}'", mesosTask.getTaskId.getValue)
                  self ! TaskLaunchSourceDelegate.TaskLaunchRejected(mesosTask, "could not save task")
                  Seq.empty
              }
          }

          log.info("Request to launch task with id '{}', version '{}'. {}",
            mesosTask.getTaskId.getValue, app.version, status)

          updateActorState()

          import context.dispatcher
          saveTask()
            .map(mesosTasks => MatchedTasks(offer.getId, mesosTasks.map(TaskWithSource(myselfAsLaunchSource, _))))
            .pipeTo(sender())

        case None => sender() ! MatchedTasks(offer.getId, Seq.empty)
      }
  }

  protected val receiveScheduleTaskLaunchNotificationDelay: Receive = {
    case AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout(task) if inFlight(task) =>
      val reject = TaskLaunchSourceDelegate.TaskLaunchRejected(
        task, AppTaskLauncherActor.TASK_LAUNCH_REJECTED_TIMEOUT_REASON
      )
      val cancellable = scheduleTaskLaunchTimeout(context, reject)
      inFlightTaskLaunches += task.getTaskId -> Some(cancellable)

    case schedule: AppTaskLauncherActor.ScheduleTaskLaunchNotificationTimeout =>
      log.debug("ignoring {}", schedule)
  }

  private[this] def inFlight(task: TaskInfo): Boolean = inFlight(task.getTaskId)
  private[this] def inFlight(taskId: TaskID): Boolean = inFlightTaskLaunches.contains(taskId)

  protected def scheduleTaskLaunchTimeout(
    context: ActorContext,
    message: TaskLaunchSourceDelegate.TaskLaunchRejected): Cancellable =
    {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(config.taskLaunchNotificationTimeout().milliseconds, self, message)
    }

  private[this] def backoffActive: Boolean = backOffUntil.forall(_ > clock.now())
  private[this] def shouldLaunchTasks: Boolean = tasksToLaunch > 0 && !backoffActive

  private[this] def status: String = {
    val backoffStr = backOffUntil match {
      case Some(backOffUntil) if backOffUntil > clock.now() => s"currently waiting for backoff($backOffUntil)"
      case _ => "not backing off"
    }

    s"$tasksToLaunch tasksToLaunch, ${inFlightTaskLaunches.size} in flight. $backoffStr"
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
        offerMatcherManager.removeSubscription(myselfAsOfferMatcher)(context.dispatcher)
        registeredAsMatcher = false
      }
    }
  }
}
