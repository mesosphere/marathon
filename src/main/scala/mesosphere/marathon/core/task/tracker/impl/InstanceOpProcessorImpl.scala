package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.tracker.impl.InstanceOpProcessorImpl.TaskStateOpResolver
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerConfig }
import mesosphere.marathon.core.task.{ Task, TaskStateChange, InstanceStateOp }
import mesosphere.marathon.storage.repository.TaskRepository
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[tracker] object InstanceOpProcessorImpl {

  /**
    * Maps a [[InstanceStateOp]] to the appropriate [[TaskStateChange]].
    *
    * @param directTaskTracker a TaskTracker instance that goes directly to the correct taskTracker
    *                          without going through the WhenLeaderActor indirection.
    */
  class TaskStateOpResolver(directTaskTracker: InstanceTracker) {
    private[this] val log = LoggerFactory.getLogger(getClass)

    /**
      * Maps the TaskStateOp
      *
      * * a TaskStateChange.Failure if the task does not exist OR ELSE
      * * delegates the TaskStateOp to the existing task that will then determine the state change
      */
    def resolve(op: InstanceStateOp)(implicit ec: ExecutionContext): Future[TaskStateChange] = {
      op match {
        case op: InstanceStateOp.LaunchEphemeral => updateIfNotExists(op.instanceId, op.instance)
        case op: InstanceStateOp.LaunchOnReservation => updateExistingTask(op)
        case op: InstanceStateOp.MesosUpdate => updateExistingTask(op)
        case op: InstanceStateOp.ReservationTimeout => updateExistingTask(op)
        case op: InstanceStateOp.Reserve => updateIfNotExists(op.instanceId, Instance(op.task))
        case op: InstanceStateOp.ForceExpunge => expungeTask(op.instanceId)
        case op: InstanceStateOp.Revert =>
          Future.successful(TaskStateChange.Update(newState = op.instance.tasks.head, oldState = None)) // TODO PODs
      }
    }

    private[this] def updateIfNotExists(instanceId: Instance.Id, updatedTask: Instance)(
      implicit
      ec: ExecutionContext): Future[TaskStateChange] = {
      directTaskTracker.instance(instanceId).map {
        case Some(existingTask) =>
          TaskStateChange.Failure( //
            new IllegalStateException(s"$instanceId of app [${instanceId.runSpecId}] already exists"))

        case None => TaskStateChange.Update(newState = updatedTask.tasks.head, oldState = None) // TODO PODs
      }
    }

    private[this] def updateExistingTask(op: InstanceStateOp) //
    (implicit ec: ExecutionContext): Future[TaskStateChange] = {
      directTaskTracker.instance(op.instanceId).map {
        case Some(existingInstance: Instance) =>
          existingInstance.tasks.head.update(op) // TODO PODs : calc correct task to update

        case None =>
          val taskId = op.instanceId
          TaskStateChange.Failure(new IllegalStateException(s"$taskId of app [${taskId.runSpecId}] does not exist"))
      }
    }

    private[this] def expungeTask(instanceId: Instance.Id)(implicit ec: ExecutionContext): Future[TaskStateChange] = {
      directTaskTracker.instance(instanceId).map {
        case Some(existingInstance: Instance) =>
          TaskStateChange.Expunge(existingInstance.tasks.head) // TODO PODs

        case None =>
          log.info("Ignoring ForceExpunge for [{}], task does not exist", instanceId)
          TaskStateChange.NoChange(Task.Id(instanceId.idString)) // TODO PODs
      }
    }
  }
}

/**
  * Processes durable operations on tasks by storing the updated tasks in or removing them from the task repository
  */
private[tracker] class InstanceOpProcessorImpl(
    taskTrackerRef: ActorRef,
    tasks: TaskRepository,
    stateOpResolver: TaskStateOpResolver,
    config: InstanceTrackerConfig) extends InstanceOpProcessor {
  import InstanceOpProcessor._

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Unit] = {
    val stateChange = stateOpResolver.resolve(op.stateOp)
    stateChange.flatMap {
      case change: TaskStateChange.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        tasks.delete(change.task.taskId).map { _ => InstanceTrackerActor.Ack(op.sender, change) }
          .recoverWith(tryToRecover(op)(expectedState = None, oldState = Some(change.task)))
          .flatMap { case ack: InstanceTrackerActor.Ack => notifyTaskTrackerActor(op, ack) }

      case change: TaskStateChange.Failure =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(change.cause)
        Future.successful(())

      case change: TaskStateChange.NoChange =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! change
        Future.successful(())

      case change: TaskStateChange.Update =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        tasks.store(change.newState).map { _ => InstanceTrackerActor.Ack(op.sender, change) }
          .recoverWith(tryToRecover(op)(expectedState = Some(change.newState), oldState = change.oldState))
          .flatMap { ack => notifyTaskTrackerActor(op, ack) }
    }
  }

  private[this] def notifyTaskTrackerActor(op: Operation, ack: InstanceTrackerActor.Ack)(
    implicit
    ec: ExecutionContext): Future[Unit] = {

    import akka.pattern.ask

    import scala.concurrent.duration._
    implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

    op.stateOp match {
      case _: InstanceStateOp.ForceExpunge | _: InstanceStateOp =>
        val msg = InstanceTrackerActor.StateChanged(taskChanged = TaskChanged(op.stateOp, ack.stateChange), ack)
        (taskTrackerRef ? msg).map(_ => ())

      case _ => Future{ () } // TODO(jdef) pods support
    }
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolate failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private[this] def tryToRecover(op: Operation)(expectedState: Option[Task], oldState: Option[Task])(
    implicit
    ec: ExecutionContext): PartialFunction[Throwable, Future[InstanceTrackerActor.Ack]] = {

    case NonFatal(cause) =>
      def ack(actualTaskState: Option[MarathonTask], change: TaskStateChange): InstanceTrackerActor.Ack = {
        val msg =
          if (expectedState.map(TaskSerializer.toProto) == actualTaskState) change
          else TaskStateChange.Failure(cause)
        InstanceTrackerActor.Ack(op.sender, msg)
      }

      log.warn(s"${op.instanceId} of app [${op.instanceId.runSpecId}]: try to recover from failed ${op.stateOp}", cause)

      tasks.get(Task.Id(op.instanceId.idString)).map { // TODO PODs load all tasks for instanceId
        case Some(task) =>
          val stateChange = TaskStateChange.Update(task, oldState)
          ack(Some(TaskSerializer.toProto(task)), stateChange)
        case None =>
          val stateChange = oldState match {
            case Some(oldTask) => TaskStateChange.Expunge(oldTask)
            case None => TaskStateChange.NoChange(Task.Id(op.instanceId.idString)) // TODO PODs
          }
          ack(None, stateChange)
      }.recover {
        case NonFatal(loadingFailure) =>
          log.warn(
            s"${op.instanceId} of app [${op.instanceId.runSpecId}]: instance reloading failed as well",
            loadingFailure)
          throw cause
      }
  }
}
