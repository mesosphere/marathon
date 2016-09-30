package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.termination.TaskKillConfig
import mesosphere.marathon.core.task.tracker.TaskStateOpProcessor
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.event.UnknownTaskTerminated
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.event.MesosStatusUpdateEvent

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
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: TaskKillConfig,
    clock: Clock) extends Actor with ActorLogging {
  import TaskKillServiceActor._
  import context.dispatcher

  val tasksToKill: mutable.HashMap[Task.Id, ToKill] = mutable.HashMap.empty
  val inFlight: mutable.HashMap[Task.Id, ToKill] = mutable.HashMap.empty

  val retryTimer: RetryTimer = new RetryTimer {
    override def createTimer(): Cancellable = {
      context.system.scheduler.schedule(config.killRetryTimeout, config.killRetryTimeout, self, Retry)
    }
  }

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[MesosStatusUpdateEvent])
    context.system.eventStream.subscribe(self, classOf[UnknownTaskTerminated])
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
    case KillUnknownTaskById(taskId) =>
      killUnknownTaskById(taskId)

    case KillTasks(tasks, promise) =>
      killTasks(tasks, promise)

    case Terminal(event) if inFlight.contains(event.taskId) || tasksToKill.contains(event.taskId) =>
      handleTerminal(event.taskId)

    case UnknownTaskTerminated(id, _, _) if inFlight.contains(id) || tasksToKill.contains(id) =>
      handleTerminal(id)

    case Retry =>
      retry()
  }

  def killUnknownTaskById(taskId: Task.Id): Unit = {
    log.debug("Received KillUnknownTaskById({})", taskId)
    tasksToKill.update(taskId, ToKill(taskId, maybeTask = None, attempts = 0))
    processKills()
  }

  def killTasks(tasks: Iterable[Task], promise: Promise[Done]): Unit = {
    log.debug("Adding {} tasks to queue; setting up child actor to track progress", tasks.size)
    setupProgressActor(tasks.map(_.taskId), promise)
    tasks.foreach { task =>
      tasksToKill.update(
        task.taskId,
        ToKill(task.taskId, maybeTask = Some(task), attempts = 0)
      )
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
    toKillNow.values.foreach(processKill)

    if (inFlight.isEmpty) {
      retryTimer.cancel()
    } else {
      retryTimer.setup()
    }
  }

  def processKill(toKill: ToKill): Unit = {
    val taskId = toKill.taskId

    val taskIsLost: Boolean = toKill.maybeTask.fold(false) { task =>
      task.isGone || task.isUnknown || task.isDropped || task.isUnreachable
    }

    if (taskIsLost) {
      log.warning("Expunging lost {} from state because it should be killed", taskId)
      // we will eventually be notified of a taskStatusUpdate after the task has been expunged
      stateOpProcessor.process(TaskStateOp.ForceExpunge(taskId))
    } else {
      val knownOrNot = if (toKill.maybeTask.isDefined) "known" else "unknown"
      log.warning("Killing {} {}", knownOrNot, taskId)
      driverHolder.driver.foreach(_.killTask(taskId.mesosTaskId))
    }

    val attempts = inFlight.get(taskId).fold(1)(_.attempts + 1)
    inFlight.update(taskId, ToKill(taskId, toKill.maybeTask, attempts, issued = clock.now()))
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
      case (taskId, toKill) if (toKill.issued + config.killRetryTimeout) < now =>
        log.warning("No kill ack received for {}, retrying", taskId)
        processKill(toKill)

      case _ => // ignore
    }
  }

  def isLost(task: Task): Boolean = {
    import org.apache.mesos
    task.mesosStatus.fold(false)(_.getState == mesos.Protos.TaskState.TASK_LOST)
  }
}

private[termination] object TaskKillServiceActor {

  sealed trait Request extends InternalRequest
  case class KillTasks(tasks: Iterable[Task], promise: Promise[Done]) extends Request
  case class KillUnknownTaskById(taskId: Task.Id) extends Request

  sealed trait InternalRequest
  case object Retry extends InternalRequest

  /**
    * Metadata used to track which tasks to kill and how many attempts have been made
    * @param taskId id of the task to kill
    * @param maybeTask the task, if available
    * @param attempts the number of kill attempts
    * @param issued the time of the last issued kill request
    */
  case class ToKill(
    taskId: Task.Id,
    maybeTask: Option[Task],
    attempts: Int,
    issued: Timestamp = Timestamp.zero)

  def props(
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: TaskKillConfig,
    clock: Clock): Props = Props(
    new TaskKillServiceActor(driverHolder, stateOpProcessor, config, clock))
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
