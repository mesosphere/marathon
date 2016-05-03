package mesosphere.marathon.core.launcher

import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.impl.{ OfferProcessorImpl, TaskLauncherImpl, TaskOpFactoryImpl }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.task.tracker.TaskCreationHandler
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.metrics.Metrics

/**
  * This module contains the glue code between matching tasks to resource offers
  * and actually launching the matched tasks.
  */
class LauncherModule(
    clock: Clock,
    metrics: Metrics,
    conf: MarathonConf,
    taskCreationHandler: TaskCreationHandler,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher,
    pluginManager: PluginManager) {

  lazy val offerProcessor: OfferProcessor =
    new OfferProcessorImpl(
      conf, clock,
      metrics,
      offerMatcher, taskLauncher, taskCreationHandler)

  lazy val taskLauncher: TaskLauncher = new TaskLauncherImpl(
    metrics,
    marathonSchedulerDriverHolder,
    clock)

  lazy val taskOpFactory: TaskOpFactory = new TaskOpFactoryImpl(conf, clock, pluginManager)
}
