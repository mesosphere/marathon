package mesosphere.marathon.core.task.tracker.impl

import akka.Done
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.task.tracker.InstanceTrackerUpdateStepProcessor
import mesosphere.marathon.metrics.Metrics.Timer
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Takes care of processing [[InstanceChange]]s and will be called after an instance state
  * change has been persisted in the repository
  */
private[tracker] class InstanceTrackerUpdateStepProcessorImpl(
    steps: Seq[InstanceChangeHandler],
    metrics: Metrics) extends InstanceTrackerUpdateStepProcessor {

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val stepTimers: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, s"step-${step.name}"))
  }.toMap

  log.info(
    "Started TaskTrackerUpdateStepsProcessorImpl with steps:\n{}",
    steps.map(step => s"* ${step.name}").mkString("\n"))

  override def process(change: InstanceChange)(implicit ec: ExecutionContext): Future[Done] = {
    steps.foldLeft(Future.successful(Done)) { (resultSoFar, nextStep) =>
      resultSoFar.flatMap { _ =>
        stepTimers(nextStep.name).timeFuture {
          log.debug(s"Executing ${nextStep.name} for [${change.instance.instanceId}]")
          nextStep.process(change).map { _ =>
            log.debug(s"Done with executing ${nextStep.name} for [${change.instance.instanceId}]")
            Done
          }
        }
      }
    }
  }

}
