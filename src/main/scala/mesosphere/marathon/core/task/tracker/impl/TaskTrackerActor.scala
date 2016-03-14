package mesosphere.marathon.core.task.tracker.impl

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.{ TaskTrackerUpdateSubscriber, TaskTracker }
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.slf4j.LoggerFactory

object TaskTrackerActor {
  def props(metrics: ActorMetrics, taskLoader: TaskLoader, taskUpdaterProps: ActorRef => Props): Props = {
    Props(new TaskTrackerActor(metrics, taskLoader, taskUpdaterProps))
  }

  /** Query the current [[TaskTracker.AppTasks]] from the [[TaskTrackerActor]]. */
  private[impl] case object List

  /** Forward an update operation to the child [[TaskUpdateActor]]. */
  private[impl] case class ForwardTaskOp(deadline: Timestamp, taskId: Task.Id, taskStateOp: TaskStateOp)

  /** Describes where and what to send after an update event has beend processed by the [[TaskTrackerActor]]. */
  private[impl] case class Ack(initiator: ActorRef, msg: Any = ()) {
    def sendAck(): Unit = initiator ! msg
  }

  /** Inform the [[TaskTrackerActor]] of an updated task (after persistence). */
  private[impl] case class TaskUpdated(stateChange: TaskStateChange.Update, ack: Ack)
  /** Inform the [[TaskTrackerActor]] of a removed task (after persistence). */
  private[impl] case class TaskRemoved(stateChange: TaskStateChange.Expunge, ack: Ack)

  private[tracker] class ActorMetrics(metrics: Metrics) {
    val stagedCount = metrics.gauge("service.mesosphere.marathon.task.staged.count", new AtomicIntGauge)
    val runningCount = metrics.gauge("service.mesosphere.marathon.task.running.count", new AtomicIntGauge)

    def resetMetrics(): Unit = {
      stagedCount.setValue(0)
      runningCount.setValue(0)
    }
  }

  private[impl] case class Subscribe(appId: PathId, subscriber: TaskTrackerUpdateSubscriber)

  private[impl] case class Unsubscribe(appId: PathId, subscriber: TaskTrackerUpdateSubscriber)
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
  private[this] var subscribersByApp: Map[PathId, Set[TaskTrackerUpdateSubscriber]] = Map.empty

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
    case appTasks: TaskTracker.TasksByApp =>
      log.info("Task loading complete.")

      unstashAll()
      context.become(withTasks(appTasks, TaskCounts(appTasks.allTasks, healthStatuses = Map.empty)))

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private[this] def withTasks(appTasks: TaskTracker.TasksByApp, counts: TaskCounts): Receive = {

    def becomeWithUpdatedApp(appId: PathId)(taskId: Task.Id, newTask: Option[Task]): Unit = {
      val updatedAppTasks = newTask match {
        case None       => appTasks.updateApp(appId)(_.withoutTask(taskId))
        case Some(task) => appTasks.updateApp(appId)(_.withTask(task))
      }

      val updatedCounts = {
        val oldTask = appTasks.task(taskId)
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

      case ForwardTaskOp(deadline, taskId, taskStateOp) =>
        val op = TaskOpProcessor.Operation(deadline, sender(), taskId, taskStateOp)
        updaterRef.forward(TaskUpdateActor.ProcessTaskOp(op))

      case msg @ TaskTrackerActor.TaskUpdated(stateChange, ack) =>
        val task = stateChange.updatedTask
        becomeWithUpdatedApp(task.appId)(task.taskId, newTask = Some(task))
        notifySubscribers(task.appId, stateChange)
        ack.sendAck()

      case msg @ TaskTrackerActor.TaskRemoved(stateChange, ack) =>
        val taskId = stateChange.taskId
        becomeWithUpdatedApp(taskId.appId)(taskId, newTask = None)
        notifySubscribers(taskId.appId, stateChange)
        ack.sendAck()

      case TaskTrackerActor.Subscribe(appId, subscriber) =>
        val subscribers = subscribersByApp.get(appId).map(_ + subscriber).getOrElse(Set(subscriber))
        subscribersByApp += appId -> subscribers

      case TaskTrackerActor.Unsubscribe(appId, subscriber) =>
        val subscribers = subscribersByApp.get(appId).map(_ - subscriber).getOrElse(Set.empty)
        if (subscribers.nonEmpty) {
          subscribersByApp += appId -> subscribers
        }
        else {
          subscribersByApp - appId
        }
    }
  }

  private[this] def notifySubscribers(appId: PathId, stateChange: TaskStateChange): Unit = {
    subscribersByApp.get(appId).foreach(_.foreach { subscriber =>
      subscriber.handleTaskStateChange(stateChange)
    })
  }
}
