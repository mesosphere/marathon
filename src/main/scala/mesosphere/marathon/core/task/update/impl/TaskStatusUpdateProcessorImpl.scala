package mesosphere.marathon.core.task.update.impl

import javax.inject.Inject

import com.google.inject.name.Names
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, InstanceTracker }
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.{ Task, InstanceStateOp }
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
    instanceTracker: InstanceTracker,
    stateOpProcessor: TaskStateOpProcessor,
    driverHolder: MarathonSchedulerDriverHolder,
    killService: TaskKillService) extends TaskStatusUpdateProcessor {
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

    instanceTracker.instance(Instance.Id(taskId)).flatMap {
      case Some(instance) =>
        val instanceStateOp = InstanceStateOp.MesosUpdate(instance, status, now)
        stateOpProcessor.process(instanceStateOp).flatMap(_ => acknowledge(status))

      case None if killWhenUnknown(status) =>
        killUnknownTaskTimer {
          log.warn("Kill unknown {}", taskId)
          killService.killUnknownTask(taskId, TaskKillReason.Unknown)
          acknowledge(status)
        }

      case maybeTask: Option[Instance] =>
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

  private[this] val ignoreWhenUnknown = Set(
    MesosProtos.TaskState.TASK_KILLED,
    MesosProtos.TaskState.TASK_KILLING,
    MesosProtos.TaskState.TASK_ERROR,
    MesosProtos.TaskState.TASK_FAILED,
    MesosProtos.TaskState.TASK_FINISHED,
    MesosProtos.TaskState.TASK_LOST
  )
  // It doesn't make sense to kill an unknown task if it is in a terminal or killing state
  // We'd only get another update for the same task
  private def killWhenUnknown(status: MesosProtos.TaskStatus): Boolean = {
    !ignoreWhenUnknown.contains(status.getState)
  }

  private def taskKnownOrNotStr(maybeTask: Option[Instance]): String = if (maybeTask.isDefined) "known" else "unknown"
}
