package mesosphere.marathon
package core.launcher

import java.time.Clock

import mesosphere.marathon.core.launcher.impl.{InstanceOpFactoryImpl, OfferProcessorImpl, TaskLauncherImpl}
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.tracker.InstanceTracker

/**
  * This module contains the glue code between matching tasks to resource offers
  * and actually launching the matched tasks.
  */
class LauncherModule(
    conf: MarathonConf,
    instanceTracker: InstanceTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher,
    pluginManager: PluginManager)(implicit clock: Clock) {

  lazy val offerProcessor: OfferProcessor =
    new OfferProcessorImpl(
      conf,
      offerMatcher, taskLauncher, instanceTracker
    )

  lazy val taskLauncher: TaskLauncher = new TaskLauncherImpl(
    marathonSchedulerDriverHolder)

  lazy val taskOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(conf, pluginManager)
}
