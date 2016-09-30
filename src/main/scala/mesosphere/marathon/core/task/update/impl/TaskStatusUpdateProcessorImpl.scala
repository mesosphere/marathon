package mesosphere.marathon.core.task.update.impl

import javax.inject.Inject

import akka.event.EventStream
import com.google.inject.name.Names
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event.UnknownTaskTerminated
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
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
    driverHolder: MarathonSchedulerDriverHolder,
    killService: TaskKillService,
    eventStream: EventStream) extends TaskStatusUpdateProcessor {
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
    val marathonTaskStatus = MarathonTaskStatus(status)

    taskTracker.task(taskId).flatMap {
      case Some(task) =>
        val taskStateOp = TaskStateOp.MesosUpdate(task, status, now)
        stateOpProcessor.process(taskStateOp).flatMap(_ => acknowledge(status))

      case None if terminalUnknown(marathonTaskStatus) =>
        log.warn("Received terminal status update for unknown {}", taskId)
        eventStream.publish(UnknownTaskTerminated(taskId, taskId.runSpecId, marathonTaskStatus))
        acknowledge(status)

      case None if killWhenUnknown(marathonTaskStatus) =>
        killUnknownTaskTimer {
          log.warn("Kill unknown {}", taskId)
          killService.killUnknownTask(taskId, TaskKillReason.Unknown)
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
}

object TaskStatusUpdateProcessorImpl {
  lazy val name = Names.named(getClass.getSimpleName)

  /** Matches all states that are considered terminal for an unknown task */
  private[impl] def terminalUnknown(status: MarathonTaskStatus): Boolean = status match {
    case t: MarathonTaskStatus.Terminal => true
    case MarathonTaskStatus.Unreachable => true
    case _ => false
  }

  private[this] val ignoreWhenUnknown = Set(
    MarathonTaskStatus.Killed,
    MarathonTaskStatus.Killing,
    MarathonTaskStatus.Error,
    MarathonTaskStatus.Failed,
    MarathonTaskStatus.Finished,
    MarathonTaskStatus.Unreachable,
    MarathonTaskStatus.Gone,
    MarathonTaskStatus.Dropped,
    MarathonTaskStatus.Unknown
  )
  // It doesn't make sense to kill an unknown task if it is in a terminal or killing state
  // We'd only get another update for the same task
  private def killWhenUnknown(status: MarathonTaskStatus): Boolean = {
    !ignoreWhenUnknown.contains(status)
  }

  private def taskKnownOrNotStr(maybeTask: Option[Task]): String = if (maybeTask.isDefined) "known" else "unknown"
}
