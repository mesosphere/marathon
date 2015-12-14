package mesosphere.marathon.core.task.tracker.impl

import javax.inject.Inject

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.tracker.{ TaskStatusUpdateProcessor, TaskStatusUpdateStep }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Executes the given TaskStatusUpdateSteps for every update.
  */
class TaskStatusUpdateProcessorImpl @Inject() (
    metrics: Metrics,
    clock: Clock,
    taskIdUtil: TaskIdUtil,
    taskTracker: TaskTracker,
    driverHolder: MarathonSchedulerDriverHolder,
    steps: Seq[TaskStatusUpdateStep]) extends TaskStatusUpdateProcessor {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val publishFutureTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "publishFuture"))

  private[this] val killUnknownTaskTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "killUnknownTask"))

  private[this] val stepTimers: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, s"step-${step.name}"))
  }.toMap

  log.info("Started status update processor with steps:\n{}", steps.map(step => s"* ${step.name}").mkString("\n"))

  override def publish(status: TaskStatus): Future[Unit] = publishFutureTimer.timeFuture {
    val now = clock.now()
    val taskId = status.getTaskId
    val appId = taskIdUtil.appId(taskId)

    val maybeTask = taskTracker.getTask(appId, taskId.getValue)

    maybeTask match {
      case Some(task) =>
        processUpdate(
          timestamp = now,
          appId = appId,
          task = task,
          mesosStatus = status
        ).map(_ => acknowledge(status))
      case None =>
        killUnknownTaskTimer {
          if (status.getState != TaskState.TASK_LOST) {
            // If we kill a unknown task, we will get another TASK_LOST notification which leads to an endless
            // stream of kills and TASK_LOST updates.
            killTask(taskId)
          }
          acknowledge(status)
          Future.successful(())
        }
    }
  }

  private[this] def acknowledge(taskStatus: TaskStatus): Unit = {
    driverHolder.driver.foreach(_.acknowledgeStatusUpdate(taskStatus))
  }

  private[this] def killTask(taskId: TaskID): Unit = {
    driverHolder.driver.foreach(_.killTask(taskId))
  }

  private[this] def processUpdate(
    timestamp: Timestamp,
    appId: PathId,
    task: MarathonTask,
    mesosStatus: TaskStatus): Future[Unit] = {
    steps.foldLeft(Future.successful(())) { (resultSoFar, nextStep) =>
      stepTimers(nextStep.name).timeFuture {
        resultSoFar.flatMap { _ =>
          log.debug("Executing {} for [{}]", Array[Object](nextStep.name, mesosStatus.getTaskId.getValue): _*)
          nextStep.processUpdate(timestamp, appId, task, mesosStatus).map { _ =>
            log.debug(
              "Done with executing {} for [{}]",
              Array[Object](nextStep.name, mesosStatus.getTaskId.getValue): _*
            )
          }
        }
      }
    }
  }
}
