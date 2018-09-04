package mesosphere.marathon
package core.launcher

import java.time.Clock

import mesosphere.marathon.core.launcher.impl.{InstanceOpFactoryImpl, OfferProcessorImpl, TaskLauncherImpl}
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics

/**
  * This module contains the glue code between matching tasks to resource offers
  * and actually launching the matched tasks.
  */
class LauncherModule(
    metrics: Metrics,
    conf: MarathonConf,
    instanceTracker: InstanceTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher,
    pluginManager: PluginManager)(implicit clock: Clock) {

  lazy val offerProcessor: OfferProcessor =
    new OfferProcessorImpl(
      metrics, conf,
      offerMatcher, taskLauncher, instanceTracker
    )

  lazy val taskLauncher: TaskLauncher = new TaskLauncherImpl(
    metrics,
    marathonSchedulerDriverHolder)

  lazy val taskOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(metrics, conf, pluginManager)
}
