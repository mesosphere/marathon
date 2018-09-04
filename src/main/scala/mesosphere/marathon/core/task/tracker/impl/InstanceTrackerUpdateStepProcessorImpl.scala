package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}
import mesosphere.marathon.core.task.tracker.InstanceTrackerUpdateStepProcessor
import mesosphere.marathon.metrics.{Metrics, Timer}
import mesosphere.marathon.metrics.deprecated.ServiceMetric

import scala.concurrent.{ExecutionContext, Future}

/**
  * Takes care of processing [[InstanceChange]]s and will be called after an instance state
  * change has been persisted in the repository
  */
private[tracker] class InstanceTrackerUpdateStepProcessorImpl(
    metrics: Metrics,
    steps: Seq[InstanceChangeHandler]) extends InstanceTrackerUpdateStepProcessor with StrictLogging {

  private[this] val oldStepTimeMetrics: Map[String, Timer] = steps.map { step =>
    step.name -> metrics.deprecatedTimer(ServiceMetric, getClass, s"step-${step.name}")
  }(collection.breakOut)
  private[this] val newStepTimeMetrics: Map[String, Timer] = steps.map { step =>
    step.metricName -> metrics.timer(s"debug.instance-tracker.update-steps.${step.metricName}.duration")
  }(collection.breakOut)

  logger.info(
    "Started TaskTrackerUpdateStepsProcessorImpl with steps:\n{}",
    steps.map(step => s"* ${step.name}").mkString("\n"))

  override def process(change: InstanceChange)(implicit ec: ExecutionContext): Future[Done] = {
    steps.foldLeft(Future.successful(Done)) { (resultSoFar, nextStep) =>
      resultSoFar.flatMap { _ =>
        oldStepTimeMetrics(nextStep.name) {
          newStepTimeMetrics(nextStep.metricName) {
            logger.debug(s"Executing ${nextStep.name} for [${change.instance.instanceId}]")
            nextStep.process(change).map { _ =>
              logger.debug(s"Done with executing ${nextStep.name} for [${change.instance.instanceId}]")
              Done
            }
          }
        }
      }
    }
  }

}
