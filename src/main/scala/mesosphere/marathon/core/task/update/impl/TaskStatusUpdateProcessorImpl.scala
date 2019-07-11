package mesosphere.marathon
package core.task.update.impl

import java.time.Clock
import java.util.Locale

import javax.inject.Inject
import akka.event.EventStream
import com.google.inject.name.Names
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.UnknownInstanceTerminated
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.metrics.{Counter, Metrics, Timer}
import org.apache.mesos.{Protos => MesosProtos}

import scala.collection.JavaConverters._
import scala.concurrent.Future

/**
  * Executes the given TaskStatusUpdateSteps for every update.
  */
class TaskStatusUpdateProcessorImpl @Inject() (
    metrics: Metrics,
    clock: Clock,
    instanceTracker: InstanceTracker,
    driverHolder: MarathonSchedulerDriverHolder,
    killService: KillService,
    launchQueue: LaunchQueue,
    eventStream: EventStream) extends TaskStatusUpdateProcessor with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val publishTimeMetric: Timer =
    metrics.timer("debug.publishing-task-status-update-duration")
  private[this] val killUnknownTaskTimeMetric: Timer =
    metrics.timer("debug.killing-unknown-task-duration")

  private[this] val taskStateCounterMetrics: collection.concurrent.Map[Int, Counter] =
    new java.util.concurrent.ConcurrentHashMap[Int, Counter]().asScala

  private[this] def getTaskStateCounterMetric(taskState: MesosProtos.TaskState): Counter = {
    def createCounter() = {
      val stateName = taskState.name().toLowerCase(Locale.US).replace('_', '-')
      val metricName = s"mesos.task-updates.$stateName"
      metrics.counter(metricName)
    }
    taskStateCounterMetrics.getOrElseUpdate(taskState.getNumber, createCounter)
  }

  logger.info("Started status update processor")

  override def publish(status: MesosProtos.TaskStatus): Future[Unit] = publishTimeMetric {
    logger.debug(s"Received status update\n$status")
    getTaskStateCounterMetric(status.getState).increment()

    import TaskStatusUpdateProcessorImpl._

    // TODO: should be Timestamp.fromTaskStatus(status), but this breaks unit tests as there are invalid stubs
    val now = clock.now()
    val taskId = Task.Id.parse(status.getTaskId)
    val taskCondition = TaskCondition(status)

    def taskIsUnknown(instance: Instance, taskId: Task.Id) = {
      instance.tasksMap.get(taskId).isEmpty
    }

    instanceTracker.instance(taskId.instanceId).flatMap {
      case Some(instance) if taskIsUnknown(instance, taskId) =>
        if (killWhenUnknown(taskCondition)) {
          killUnknownTaskTimeMetric {
            logger.warn(s"Kill $taskId because it's unknown to marathon. " +
              s"The related instance ${instance.instanceId} is associated with ${instance.tasksMap.keys}")
            Future.successful(killService.killUnknownTask(taskId, KillReason.NotInSync))
          }
        }
        checkDelay(instance, status)
        acknowledge(status)

      case Some(instance) =>
        // TODO(PODS): we might as well pass the taskCondition here
        instanceTracker.updateStatus(instance, status, now).flatMap{ _ =>
          checkDelay(instance, status)
          acknowledge(status)
        }

      case None if terminalUnknown(taskCondition) =>
        logger.warn(s"Received terminal status update for unknown $taskId")
        eventStream.publish(UnknownInstanceTerminated(taskId.instanceId, taskId.runSpecId, taskCondition))
        acknowledge(status)

      case None if killWhenUnknown(taskCondition) =>
        killUnknownTaskTimeMetric {
          logger.warn(s"Kill unknown $taskId")
          killService.killUnknownTask(taskId, KillReason.Unknown)
          acknowledge(status)
        }

      case maybeInstance: Option[Instance] =>
        val taskStr = taskKnownOrNotStr(maybeInstance)
        logger.info(s"Ignoring ${status.getState} update for $taskStr $taskId")
        acknowledge(status)
    }
  }

  /**
    * @return true, if the received status indicates that the agent it comes from is being drained.
    *
    * The only relevant states here are TASK_KILLED and TASK_GONE_BY_OPERATOR. See the design doc
    * https://docs.google.com/document/d/1w3O80NFE6m52XNMv7EdXSO-1NebEs8opA8VZPG1tW0Y/edit#
    * for more info
    */
  def agentDraining(status: MesosProtos.TaskStatus): Boolean = {
    val relevantState = status.hasState &&
      (status.getState == MesosProtos.TaskState.TASK_KILLED || status.getState == MesosProtos.TaskState.TASK_GONE_BY_OPERATOR)
    val isDraining = status.hasReason && status.getReason == MesosProtos.TaskStatus.Reason.REASON_SLAVE_DRAINING

    relevantState && isDraining
  }

  /**
    * When receiving task statuses from draining agents, we reset any delay
    * that might exist for the related runSpec. This is to speed up the
    * process of replacing these tasks.
    */
  def checkDelay(instance: Instance, status: MesosProtos.TaskStatus): Unit = {
    // reset any delay for the related runSpec if the agent is being drained
    if (agentDraining(status)) {
      logger.info(s"Reset delay for ${instance.instanceId.runSpecId} because status update for ${status.getTaskId.getValue} indicated ${status.getSlaveId.getValue} is being drained")
      launchQueue.resetDelay(instance.runSpec)
    }
  }

  private[this] def acknowledge(status: MesosProtos.TaskStatus): Future[Unit] = {
    driverHolder.driver.foreach{ driver =>
      logger.info(s"Acknowledge status update for task ${status.getTaskId.getValue}: ${status.getState} (${status.getMessage})")
      driver.acknowledgeStatusUpdate(status)
    }
    Future.successful(())
  }
}

object TaskStatusUpdateProcessorImpl {
  lazy val name = Names.named(getClass.getSimpleName)

  /** Matches all states that are considered terminal for an unknown task */
  def terminalUnknown(condition: Condition): Boolean = condition match {
    case t: Condition.Terminal => true
    case Condition.Unreachable => true
    case _ => false
  }

  // TODO(PODS): align this with similar extractors/functions
  private[this] val ignoreWhenUnknown = Set[Condition](
    Condition.Killed,
    Condition.Killing,
    Condition.Error,
    Condition.Failed,
    Condition.Finished,
    Condition.Unreachable,
    Condition.Gone,
    Condition.Dropped,
    Condition.Unknown
  )
  // It doesn't make sense to kill an unknown task if it is in a terminal or killing state
  // We'd only get another update for the same task
  private def killWhenUnknown(condition: Condition): Boolean = {
    !ignoreWhenUnknown.contains(condition)
  }

  private def taskKnownOrNotStr(maybeTask: Option[Instance]): String = if (maybeTask.isDefined) "known" else "unknown"
}
