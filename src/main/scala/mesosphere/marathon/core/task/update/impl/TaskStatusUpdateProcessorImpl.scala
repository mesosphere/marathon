package mesosphere.marathon.core.task.update.impl

import javax.inject.Inject

import com.google.inject.name.Names
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Executes the given TaskStatusUpdateSteps for every update.
  */
class TaskStatusUpdateProcessorImpl @Inject() (
    metrics: Metrics,
    clock: Clock,
    taskTracker: TaskTracker,
    stateOpProcessor: TaskStateOpProcessor,
    driverHolder: MarathonSchedulerDriverHolder) extends TaskStatusUpdateProcessor {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val publishTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "publishFuture"))

  private[this] val killUnknownTaskTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "killUnknownTask"))

  log.info("Started status update processor")

  override def publish(status: MesosProtos.TaskStatus): Future[Unit] = publishTimer.timeFuture {
    import TaskStatusUpdateProcessorImpl._

    val now = clock.now()
    val taskId = Task.Id(status.getTaskId)

    taskTracker.task(taskId).flatMap {
      case Some(task) if task.launched.isDefined =>
        val taskStateOp = TaskStateOp.MesosUpdate(task, MarathonTaskStatus(status), status, now)
        stateOpProcessor.process(taskStateOp).flatMap(_ => acknowledge(status))

      case maybeTask: Option[Task] if killWhenUnknownOrNotLaunched(status) =>
        killUnknownTaskTimer {
          val taskStr = taskKnownOrNotStr(maybeTask)
          log.warn(s"Kill $taskStr $taskId")
          killTask(taskId.mesosTaskId)
          acknowledge(status)
        }

      case maybeTask: Option[Task] =>
        val taskStr = taskKnownOrNotStr(maybeTask)
        log.info(s"Ignoring ${status.getState} update for $taskStr $taskId")
        acknowledge(status)
    }
  }

  private[this] def acknowledge(taskStatus: MesosProtos.TaskStatus): Future[Unit] = {
    driverHolder.driver.foreach(_.acknowledgeStatusUpdate(taskStatus))
    Future.successful(())
  }

  private[this] def killTask(taskId: MesosProtos.TaskID): Unit = {
    driverHolder.driver.foreach(_.killTask(taskId))
  }
}

object TaskStatusUpdateProcessorImpl {
  lazy val name = Names.named(getClass.getSimpleName)

  private[this] val ignoreWhenUnknown = Set(MesosProtos.TaskState.TASK_KILLING, MesosProtos.TaskState.TASK_LOST)
  // If we kill an unknown task, we will get another TASK_LOST notification which leads to an endless
  // stream of kills and TASK_LOST updates.
  // In addition, we won't kill unknown tasks when they're in state TASK_KILLING
  private def killWhenUnknownOrNotLaunched(status: MesosProtos.TaskStatus): Boolean = {
    !ignoreWhenUnknown.contains(status.getState)
  }

  private def taskKnownOrNotStr(maybeTask: Option[Task]): String = if (maybeTask.isDefined) "known" else "unknown"
}
