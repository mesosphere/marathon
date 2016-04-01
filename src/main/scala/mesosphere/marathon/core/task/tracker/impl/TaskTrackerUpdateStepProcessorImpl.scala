package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.tracker.TaskTrackerUpdateStepProcessor
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.metrics.{ Metrics, MetricPrefixes }
import mesosphere.marathon.metrics.Metrics.Timer
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Takes care of performing the given [[TaskUpdateStep]]s and should be called after a task state
  * change has been persisted in the repository
  */
private[tracker] class TaskTrackerUpdateStepProcessorImpl(
    steps: Seq[TaskUpdateStep],
    metrics: Metrics) extends TaskTrackerUpdateStepProcessor {

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val stepTimers: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, s"step-${step.name}"))
  }.toMap

  log.info("Started TaskTrackerUpdateStepsProcessorImpl with steps:\n{}",
    steps.map(step => s"* ${step.name}").mkString("\n"))

  override def process(taskChanged: TaskChanged)(implicit ec: ExecutionContext): Future[Unit] = {
    steps.foldLeft(Future.successful(())) { (resultSoFar, nextStep) =>
      resultSoFar.flatMap { _ =>
        stepTimers(nextStep.name).timeFuture {
          log.debug("Executing {} for [{}]", Array[Object](nextStep.name, taskChanged.taskId.idString): _*)
          nextStep.processUpdate(taskChanged).map { _ =>
            log.debug(
              "Done with executing {} for [{}]",
              Array[Object](nextStep.name, taskChanged.taskId.idString): _*
            )
          }
        }
      }
    }
  }

}
