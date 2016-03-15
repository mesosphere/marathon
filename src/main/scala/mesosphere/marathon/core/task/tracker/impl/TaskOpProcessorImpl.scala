package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.TaskStateOpResolver
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.{ PathId, TaskRepository, Timestamp }
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[tracker] object TaskOpProcessorImpl {

  /**
    * Maps a [[TaskStateOp]] to the appropriate [[TaskStateChange]].
    *
    * @param directTaskTracker a TaskTracker instance that goes directly to the correct taskTracker
    *                          without going through the WhenLeaderActor indirection.
    */
  class TaskStateOpResolver(directTaskTracker: TaskTracker) {
    private[this] val log = LoggerFactory.getLogger(getClass)

    /**
      * Maps the TaskStateOp
      *
      * * a TaskStateChange.Failure if the task does not exist OR ELSE
      * * delegates the TaskStateOp to the existing task that will then determine the state change
      */
    def resolve(op: TaskStateOp)(implicit ec: ExecutionContext): Future[TaskStateChange] = {
      // FIXME (3221): Create and Expunge are mostly to restore states.
      op match {
        case op: TaskStateOp.Create              => changeIfNotExists(op.taskId, TaskStateChange.Update(op.task))
        case op: TaskStateOp.ForceExpunge        => updateExistingTask(op)
        case op: TaskStateOp.LaunchOnReservation => updateExistingTask(op)
        case op: TaskStateOp.MesosUpdate         => updateExistingTask(op)
        case op: TaskStateOp.ReservationTimeout  => updateExistingTask(op)
        case op: TaskStateOp.Reserve             => updateExistingTask(op)
      }
    }

    private[this] def changeIfNotExists(taskId: Task.Id, stateChange: TaskStateChange)(
      implicit ec: ExecutionContext): Future[TaskStateChange] = {
      directTaskTracker.task(taskId).map {
        case Some(existingTask) =>
          TaskStateChange.Failure(new IllegalStateException(s"$taskId of app [${taskId.appId}] already exists"))

        case None => stateChange
      }
    }

    private[this] def updateExistingTask(op: TaskStateOp)(implicit ec: ExecutionContext): Future[TaskStateChange] = {
      directTaskTracker.task(op.taskId).map {
        case Some(existingTask) =>
          existingTask.update(op)

        case None =>
          val taskId = op.taskId
          TaskStateChange.Failure(new IllegalStateException(s"$taskId of app [${taskId.appId}] does not exist"))
      }
    }
  }
}

/**
  * Processes durable operations on tasks by
  *
  * * storing the updated tasks in the task repository
  * * informing the taskTracker actor of the latest task state
  */
private[tracker] class TaskOpProcessorImpl(
    taskTrackerRef: ActorRef,
    repo: TaskRepository,
    stateOpResolver: TaskStateOpResolver,
    steps: Seq[TaskStatusUpdateStep],
    metrics: Metrics) extends TaskOpProcessor {
  import TaskOpProcessor._

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val stepTimers: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, s"step-${step.name}"))
  }.toMap

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Unit] = {
    val stateChange = stateOpResolver.resolve(op.stateOp)

    // FIXME (3221): TaskUpdated/TaskRemoved can be done via a NotifyTaskTrackerStep
    stateChange.flatMap {
      case stateChange: TaskStateChange.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        repo.expunge(op.taskId.idString).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(TaskStateChange.Expunge(op.taskId),
            TaskTrackerActor.Ack(op.sender, stateChange))
        }.flatMap { _ =>
          notifyOthers(op.stateOp, stateChange)
        }.recoverWith(tryToRecover(op)(expectedTaskState = None))

      case stateChange: TaskStateChange.Failure =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(stateChange.cause)
        Future.successful(())

      case stateChange: TaskStateChange.NoChange =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! stateChange
        Future.successful(())

      case stateChange: TaskStateChange.Update =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        val marathonTask = TaskSerializer.toProto(stateChange.updatedTask)
        repo.store(marathonTask).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(stateChange, TaskTrackerActor.Ack(op.sender, stateChange))
        }.flatMap { _ =>
          notifyOthers(op.stateOp, stateChange)
        }.recoverWith(tryToRecover(op)(expectedTaskState = Some(stateChange.updatedTask)))
    }
  }

  private[this] def notifyOthers(stateOp: TaskStateOp, stateChange: TaskStateChange): Future[Unit] = {
    // compatibility: if it's a statusUpdate that resulted in a Update or Expunge, do as before

    (stateOp, stateChange) match {
      case (TaskStateOp.MesosUpdate(taskId, MarathonTaskStatus.WithMesosStatus(mesosStatus), now),
        update: TaskStateChange.Update) =>
        processStatusUpdateSteps(now, taskId.appId, update.updatedTask, mesosStatus)
      case _ => Future.successful(())
    }
  }

  private[this] def processStatusUpdateSteps(
    timestamp: Timestamp,
    appId: PathId,
    task: Task,
    mesosStatus: MesosProtos.TaskStatus): Future[Unit] = {

    import scala.concurrent.ExecutionContext.Implicits.global
    steps.foldLeft(Future.successful(())) { (resultSoFar, nextStep) =>
      resultSoFar.flatMap { _ =>
        stepTimers(nextStep.name).timeFuture {
          log.debug("Executing {} for [{}]", Array[Object](nextStep.name, mesosStatus.getTaskId.getValue): _*)
          nextStep.processUpdate(timestamp, task, mesosStatus).map { _ =>
            log.debug(
              "Done with executing {} for [{}]",
              Array[Object](nextStep.name, mesosStatus.getTaskId.getValue): _*
            )
          }
        }
      }
    }
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolated failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private[this] def tryToRecover(op: Operation)(expectedTaskState: Option[Task])(
    implicit ec: ExecutionContext): PartialFunction[Throwable, Future[Unit]] = {

    case NonFatal(cause) =>
      def ack(actualTaskState: Option[MarathonTask]): TaskTrackerActor.Ack = {
        val msg = if (expectedTaskState.map(_.marathonTask) == actualTaskState) (()) else Status.Failure(cause)
        TaskTrackerActor.Ack(op.sender, msg)
      }

      log.warn(s"${op.taskId} of app [${op.taskId.appId}]: try to recover from failed ${op.stateOp.toString}", cause)

      repo.task(op.taskId.idString).map {
        case Some(task) =>
          val taskState = TaskSerializer.fromProto(task)
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(TaskStateChange.Update(taskState), ack(Some(task)))
        case None =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(TaskStateChange.Expunge(op.taskId), ack(None))
      }.recover {
        case NonFatal(loadingFailure) =>
          log.warn(s"${op.taskId} of app [${op.taskId.appId}]: task reloading failed as well", loadingFailure
          )
          throw cause
      }
  }
}
