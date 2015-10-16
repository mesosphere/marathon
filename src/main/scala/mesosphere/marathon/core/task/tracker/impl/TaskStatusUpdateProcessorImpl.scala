package mesosphere.marathon.core.task.tracker.impl

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.tracker.{ TaskStatusUpdateProcessor, TaskStatusUpdateStep }
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import org.apache.mesos.Protos.TaskStatus
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
    steps: Seq[TaskStatusUpdateStep]) extends TaskStatusUpdateProcessor {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val publishFutureTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "publishFuture"))

  private[this] val stepTimers: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, s"step-${step.name}"))
  }.toMap

  log.info("Started status update processor with steps:\n{}", steps.map(step => s"* ${step.name}").mkString("\n"))

  override def publish(status: TaskStatus): Future[Unit] = publishFutureTimer.timeFuture {
    val now = clock.now()
    val taskId = status.getTaskId
    val appId = taskIdUtil.appId(taskId)

    val maybeTask = taskTracker.fetchTask(taskId.getValue)

    processUpdate(
      timestamp = now,
      appId = appId,
      maybeTask = maybeTask,
      mesosStatus = status
    )
  }

  private[this] def processUpdate(
    timestamp: Timestamp,
    appId: PathId,
    maybeTask: Option[MarathonTask],
    mesosStatus: TaskStatus): Future[Unit] = {
    steps.foldLeft(Future.successful(())) { (resultSoFar, nextStep) =>
      stepTimers(nextStep.name).timeFuture {
        resultSoFar.flatMap { _ =>
          log.debug("Executing {} for [{}]", Array[Object](nextStep.name, mesosStatus.getTaskId.getValue): _*)
          nextStep.processUpdate(timestamp, appId, maybeTask, mesosStatus).map { _ =>
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
