package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.termination.TaskKillConfig
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.core.event.MesosStatusUpdateEvent
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation

import scala.collection.mutable
import scala.concurrent.Promise

/**
  * An actor that handles killing tasks in chunks and depending on the task state.
  * Lost tasks will simply be expunged from state, while active tasks will be killed
  * via the scheduler driver. There is be a maximum number of kills in flight, and
  * the service will only issue more kills when tasks are reported terminal.
  *
  * If a kill is not acknowledged with a terminal status update within a configurable
  * time window, the kill is retried a configurable number of times. If the maximum
  * number of retries is exceeded, the task will be expunged from state similar to a
  * lost task.
  *
  * For each kill request, a child [[TaskKillProgressActor]] will be spawned, which
  * is supposed to watch the progress and complete a given promise when all watched
  * tasks are reportedly terminal.
  *
  * See [[TaskKillConfig]] for configuration options.
  */
private[impl] class TaskKillServiceActor(
    taskTracker: InstanceTracker,
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: TaskKillConfig,
    clock: Clock) extends Actor with ActorLogging {
  import TaskKillServiceActor._
  import context.dispatcher

  val tasksToKill: mutable.HashMap[Task.Id, Option[Task]] = mutable.HashMap.empty
  val inFlight: mutable.HashMap[Task.Id, TaskToKill] = mutable.HashMap.empty

  val retryTimer: RetryTimer = new RetryTimer {
    override def createTimer(): Cancellable = {
      context.system.scheduler.schedule(config.killRetryTimeout, config.killRetryTimeout, self, Retry)
    }
  }

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[MesosStatusUpdateEvent])
  }

  override def postStop(): Unit = {
    retryTimer.cancel()
    context.system.eventStream.unsubscribe(self)
    if (tasksToKill.nonEmpty) {
      log.warning(
        "Stopping {}, but not all tasks have been killed. Remaining: {}, inFlight: {}",
        self, tasksToKill.keySet.mkString(","), inFlight.keySet.mkString(","))
    }
  }

  override def receive: Receive = {
    case KillUnknownTaskById(taskId, promise) =>
      killUnknownTaskById(taskId, promise)

    case KillTasks(tasks, promise) =>
      killTasks(tasks, promise)

    case Terminal(event) if inFlight.contains(event.taskId) || tasksToKill.contains(event.taskId) =>
      handleTerminal(event.taskId)

    case Retry =>
      retry()

    case unhandled: InternalRequest =>
      log.warning("Received unhandled {}", unhandled)
  }

  def killUnknownTaskById(taskId: Task.Id, promise: Promise[Done]): Unit = {
    log.debug("Received KillUnknownTaskById({})", taskId)
    setupProgressActor(Seq(taskId), promise)
    tasksToKill.update(taskId, None)
    processKills()
  }

  def killTasks(tasks: Iterable[Task], promise: Promise[Done]): Unit = {
    log.debug("Adding {} tasks to queue; setting up child actor to track progress", tasks.size)
    setupProgressActor(tasks.map(_.taskId), promise)
    tasks.foreach { task =>
      tasksToKill.update(task.taskId, Some(task))
    }
    processKills()
  }

  def setupProgressActor(taskIds: Iterable[Task.Id], promise: Promise[Done]): Unit = {
    context.actorOf(TaskKillProgressActor.props(taskIds, promise))
  }

  def processKills(): Unit = {
    val killCount = config.killChunkSize - inFlight.size
    val toKillNow = tasksToKill.take(killCount)

    log.info("processing {} kills", toKillNow.size)
    toKillNow.foreach {
      case (taskId, maybeTask) => processKill(taskId, maybeTask)
    }

    if (inFlight.isEmpty) {
      retryTimer.cancel()
    } else {
      retryTimer.setup()
    }
  }

  def processKill(taskId: Task.Id, maybeTask: Option[Task]): Unit = {
    val taskIsLost: Boolean = maybeTask.fold(false)(i => i.isGone || i.isUnknown || i.isDropped || i.isUnreachable)

    if (taskIsLost) {
      log.warning("Expunging lost {} from state because it should be killed", taskId)
      // we will eventually be notified of a taskStatusUpdate after the task has been expunged
      stateOpProcessor.process(InstanceUpdateOperation.ForceExpunge(Instance.Id(taskId)))
    } else {
      val knownOrNot = if (maybeTask.isDefined) "known" else "unknown"
      log.warning("Killing {} {}", knownOrNot, taskId)
      driverHolder.driver.foreach(_.killTask(taskId.mesosTaskId))
    }

    val attempts = inFlight.get(taskId).fold(1)(_.attempts + 1)
    inFlight.update(taskId, TaskToKill(taskId, maybeTask, issued = clock.now(), attempts))
    tasksToKill.remove(taskId)
  }

  def handleTerminal(taskId: Task.Id): Unit = {
    tasksToKill.remove(taskId)
    inFlight.remove(taskId)
    log.debug("{} is terminal. ({} kills queued, {} in flight)", taskId, tasksToKill.size, inFlight.size)
    processKills()
  }

  def retry(): Unit = {
    val now = clock.now()

    inFlight.foreach {
      case (taskId, taskToKill) if taskToKill.attempts >= config.killRetryMax =>
        log.warning("Expunging {} from state: max retries reached", taskId)
        stateOpProcessor.process(InstanceUpdateOperation.ForceExpunge(Instance.Id(taskId)))

      case (taskId, taskToKill) if (taskToKill.issued + config.killRetryTimeout) < now =>
        log.warning("No kill ack received for {}, retrying", taskId)
        processKill(taskId, taskToKill.maybeTask)

      case _ => // ignore
    }
  }
}

private[termination] object TaskKillServiceActor {

  sealed trait Request extends InternalRequest
  case class KillTasks(tasks: Iterable[Task], promise: Promise[Done]) extends Request
  case class KillUnknownTaskById(taskId: Task.Id, promise: Promise[Done]) extends Request

  sealed trait InternalRequest
  case object Retry extends InternalRequest

  case class TaskToKill(taskId: Task.Id, maybeTask: Option[Task], issued: Timestamp, attempts: Int)

  def props(
    taskTracker: InstanceTracker,
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: TaskKillConfig,
    clock: Clock): Props = Props(
    new TaskKillServiceActor(taskTracker, driverHolder, stateOpProcessor, config, clock))
}

/**
  * Wraps a timer into an interface that hides internal mutable state behind simple setup and cancel methods
  */
private[this] trait RetryTimer {
  private[this] var retryTimer: Option[Cancellable] = None

  /** Creates a new timer when setup() is called */
  def createTimer(): Cancellable

  /**
    * Cancel the timer if there is one.
    */
  final def cancel(): Unit = {
    retryTimer.foreach(_.cancel())
    retryTimer = None
  }

  /**
    * Setup a timer if there is no timer setup already. Will do nothing if there is a timer.
    * Note that if the timer is scheduled only once, it will not be removed until you call cancel.
    */
  final def setup(): Unit = {
    if (retryTimer.isEmpty) {
      retryTimer = Some(createTimer())
    }
  }
}
