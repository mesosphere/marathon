package mesosphere.marathon.core.task.tracker.impl

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.state.PathId
import org.slf4j.LoggerFactory

object TaskTrackerActor {
  def props(taskLoader: TaskLoader, taskUpdaterProps: ActorRef => Props): Props = {
    Props(new TaskTrackerActor(taskLoader, taskUpdaterProps))
  }

  /** Query the current [[AppDataMap]] from the [[TaskTrackerActor]]. */
  private[impl] case object List
  /** Forward an update operation to the child [[TaskUpdateActor]]. */
  private[impl] case class ForwardTaskOp(appId: PathId, taskId: String, action: TaskOpProcessor.Action)

  /** Describes where and what to send after an update event has beend processed by the [[TaskTrackerActor]]. */
  private[impl] case class Ack(initiator: ActorRef, msg: Any = ()) {
    def sendAck(): Unit = initiator ! msg
  }

  /** Inform the [[TaskTrackerActor]] of an updated task (after persistence). */
  private[impl] case class TaskUpdated(appId: PathId, task: MarathonTask, ack: Ack)
  /** Inform the [[TaskTrackerActor]] of a removed task (after persistence). */
  private[impl] case class TaskRemoved(appId: PathId, taskId: String, ack: Ack)
}

/**
  * Holds the current in-memory version of all task state. It gets informed of task state changes
  * after they have been persisted.
  *
  * It also spawns the [[TaskUpdateActor]] as a child and forwards update operations to it.
  */
private class TaskTrackerActor(taskLoader: TaskLoader, taskUpdaterProps: ActorRef => Props) extends Actor with Stash {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val updaterRef = context.actorOf(taskUpdaterProps(self), "updater")

  override val supervisorStrategy = OneForOneStrategy() { case _: Exception => Escalate }

  override def preStart(): Unit = {
    log.info(s"${getClass.getSimpleName} is starting. Task loading initiated.")

    import akka.pattern.pipe
    import context.dispatcher
    taskLoader.loadTasks().pipeTo(self)
  }

  override def receive: Receive = initializing

  private[this] def initializing: Receive = LoggingReceive.withLabel("initializing") {
    case appTasks: AppDataMap =>
      log.info("Task loading complete.")

      unstashAll()
      context.become(withTasks(appTasks))

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private[this] def withTasks(appTasks: AppDataMap): Receive = LoggingReceive.withLabel("withTasks") {
    def becomeWithUpdatedApp(appId: PathId)(update: AppData => AppData): Unit = {
      context.become(withTasks(appTasks.updateApp(appId)(update)))
    }

    LoggingReceive.withLabel("withTasks") {
      case TaskTrackerActor.List =>
        sender() ! appTasks

      case ForwardTaskOp(appId, taskId, action) =>
        val op = TaskOpProcessor.Operation(sender(), appId, taskId, action)
        updaterRef.forward(TaskUpdateActor.ProcessTaskOp(op))

      case msg @ TaskTrackerActor.TaskUpdated(appId, task, ack) =>
        becomeWithUpdatedApp(appId)(_.withTask(task))
        ack.sendAck()

      case msg @ TaskTrackerActor.TaskRemoved(appId, taskId, ack) =>
        becomeWithUpdatedApp(appId)(_.withoutTask(taskId))
        ack.sendAck()
    }
  }
}
