package mesosphere.marathon.core.task.update.impl

import javax.inject.Inject

import com.google.inject.name.Names
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object TaskStatusUpdateProcessorImpl {
  lazy val name = Names.named(getClass.getSimpleName)
}

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

  private[this] val publishFutureTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "publishFuture"))

  private[this] val killUnknownTaskTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "killUnknownTask"))

  log.info("Started status update processor")

  override def publish(status: MesosProtos.TaskStatus): Future[Unit] = publishFutureTimer.timeFuture {
    val now = clock.now()
    val taskId = Task.Id(status.getTaskId)

    taskTracker.task(taskId).flatMap {
      case _ if status.getState == MesosProtos.TaskState.TASK_KILLING =>
        // introduced in Mesos 0.28.0, not yet processed
        log.info("Ignoring TASK_KILLING update for {}", taskId)
        acknowledge(status)

      case Some(task) if task.launched.isDefined =>
        val taskStateOp = TaskStateOp.MesosUpdate(task, MarathonTaskStatus(status), now)
        stateOpProcessor.process(taskStateOp).flatMap(_ => acknowledge(status))

      case _ =>
        killUnknownTaskTimer {
          // If we kill a unknown task, we will get another TASK_LOST notification which leads to an endless
          // stream of kills and TASK_LOST updates.
          if (status.getState != MesosProtos.TaskState.TASK_LOST) {
            log.warn(s"Kill unknown $taskId")
            killTask(taskId.mesosTaskId)
          }
          acknowledge(status)
        }
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
