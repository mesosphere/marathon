package mesosphere.marathon.core.task.tracker.impl

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.state.{ Timestamp, PathId }
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Deadline

object TaskTrackerActor {
  def props(metrics: ActorMetrics, taskLoader: TaskLoader, taskUpdaterProps: ActorRef => Props): Props = {
    Props(new TaskTrackerActor(metrics, taskLoader, taskUpdaterProps))
  }

  /** Query the current [[AppDataMap]] from the [[TaskTrackerActor]]. */
  private[impl] case object List
  /** Forward an update operation to the child [[TaskUpdateActor]]. */
  private[impl] case class ForwardTaskOp(
    deadline: Timestamp, appId: PathId, taskId: String, action: TaskOpProcessor.Action)

  /** Describes where and what to send after an update event has beend processed by the [[TaskTrackerActor]]. */
  private[impl] case class Ack(initiator: ActorRef, msg: Any = ()) {
    def sendAck(): Unit = initiator ! msg
  }

  /** Inform the [[TaskTrackerActor]] of an updated task (after persistence). */
  private[impl] case class TaskUpdated(appId: PathId, task: MarathonTask, ack: Ack)
  /** Inform the [[TaskTrackerActor]] of a removed task (after persistence). */
  private[impl] case class TaskRemoved(appId: PathId, taskId: String, ack: Ack)

  private[tracker] class ActorMetrics(metrics: Metrics) {
    val stagedCount = metrics.gauge("service.mesosphere.marathon.task.staged.count", new AtomicIntGauge)
    val runningCount = metrics.gauge("service.mesosphere.marathon.task.running.count", new AtomicIntGauge)

    def resetMetrics(): Unit = {
      stagedCount.setValue(0)
      runningCount.setValue(0)
    }
  }
}

/**
  * Holds the current in-memory version of all task state. It gets informed of task state changes
  * after they have been persisted.
  *
  * It also spawns the [[TaskUpdateActor]] as a child and forwards update operations to it.
  */
private class TaskTrackerActor(
    metrics: TaskTrackerActor.ActorMetrics,
    taskLoader: TaskLoader,
    taskUpdaterProps: ActorRef => Props) extends Actor with Stash {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val updaterRef = context.actorOf(taskUpdaterProps(self), "updater")

  override val supervisorStrategy = OneForOneStrategy() { case _: Exception => Escalate }

  override def preStart(): Unit = {
    super.preStart()

    log.info(s"${getClass.getSimpleName} is starting. Task loading initiated.")
    metrics.resetMetrics()

    import akka.pattern.pipe
    import context.dispatcher
    taskLoader.loadTasks().pipeTo(self)
  }

  override def postStop(): Unit = {
    metrics.resetMetrics()

    super.postStop()
  }

  override def receive: Receive = initializing

  private[this] def initializing: Receive = LoggingReceive.withLabel("initializing") {
    case appTasks: TaskTracker.AppDataMap =>
      log.info("Task loading complete.")

      unstashAll()
      context.become(withTasks(appTasks, TaskCounts(appTasks.tasks, healthStatuses = Map.empty)))

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private[this] def withTasks(appTasks: TaskTracker.AppDataMap, counts: TaskCounts): Receive = {

    def becomeWithUpdatedApp(appId: PathId)(taskId: String, newTask: Option[MarathonTask]): Unit = {
      val updatedAppTasks = newTask match {
        case None       => appTasks.updateApp(appId)(_.withoutTask(taskId))
        case Some(task) => appTasks.updateApp(appId)(_.withTask(task))
      }

      val updatedCounts = {
        val oldTask = appTasks.getTask(appId, taskId)
        // we do ignore health counts
        val oldTaskCount = TaskCounts(oldTask, healthStatuses = Map.empty)
        val newTaskCount = TaskCounts(newTask, healthStatuses = Map.empty)
        counts + newTaskCount - oldTaskCount
      }

      context.become(withTasks(updatedAppTasks, updatedCounts))
    }

    // this is run on any state change
    metrics.stagedCount.setValue(counts.tasksStaged)
    metrics.runningCount.setValue(counts.tasksRunning)

    LoggingReceive.withLabel("withTasks") {
      case TaskTrackerActor.List =>
        sender() ! appTasks

      case ForwardTaskOp(deadline, appId, taskId, action) =>
        val op = TaskOpProcessor.Operation(deadline, sender(), appId, taskId, action)
        updaterRef.forward(TaskUpdateActor.ProcessTaskOp(op))

      case msg @ TaskTrackerActor.TaskUpdated(appId, task, ack) =>
        becomeWithUpdatedApp(appId)(task.getId, newTask = Some(task))
        ack.sendAck()

      case msg @ TaskTrackerActor.TaskRemoved(appId, taskId, ack) =>
        becomeWithUpdatedApp(appId)(taskId, newTask = None)
        ack.sendAck()
    }
  }
}
