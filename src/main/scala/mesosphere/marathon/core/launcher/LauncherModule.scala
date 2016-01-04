package mesosphere.marathon.core.launcher

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.impl.{ OfferProcessorImpl, TaskLauncherImpl }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.tasks.TaskCreator

/**
  * This module contains the glue code between matching tasks to resource offers
  * and actually launching the matched tasks.
  */
class LauncherModule(
    clock: Clock,
    metrics: Metrics,
    offerProcessorConfig: OfferProcessorConfig,
    taskCreator: TaskCreator,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher) {

  lazy val offerProcessor: OfferProcessor =
    new OfferProcessorImpl(
      offerProcessorConfig, clock,
      metrics,
      offerMatcher, taskLauncher, taskCreator)

  lazy val taskLauncher: TaskLauncher = new TaskLauncherImpl(
    metrics,
    marathonSchedulerDriverHolder,
    clock)
}
