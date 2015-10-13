package mesosphere.marathon.core.task.tracker.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.tasks.TaskTracker
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Update task tracker corresponding to the event.
  */
class UpdateTaskTrackerStepImpl @Inject() (
    taskTracker: TaskTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder) extends TaskStatusUpdateStep {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def name: String = "updateTaskTracker"

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def processUpdate(
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId

    import org.apache.mesos.Protos.TaskState._

    import scala.concurrent.ExecutionContext.Implicits.global
    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        // Remove from our internal list
        taskTracker.terminated(appId, taskId.getValue)

      case TASK_RUNNING if !maybeTask.exists(_.hasStartedAt) => // staged, not running
        taskTracker.running(appId, status)
          .recover {
            case NonFatal(t) =>
              log.warn(s"Task could not be saved: '${taskId.getValue}'", t)
              driverOpt.foreach(_.killTask(status.getTaskId))
          }

      case TASK_STAGING if !taskTracker.contains(appId) =>
        log.warn(s"Received status update for unknown app $appId, killing task ${status.getTaskId}")
        driverOpt.foreach(_.killTask(status.getTaskId))
        Future.successful(())

      case _ =>
        taskTracker.statusUpdate(appId, status).map {
          case None =>
            log.warn(s"Killing task ${status.getTaskId}")
            driverOpt.foreach(_.killTask(status.getTaskId))
          case _ =>
        }
    }
  }

  private[this] def driverOpt = marathonSchedulerDriverHolder.driver

}
