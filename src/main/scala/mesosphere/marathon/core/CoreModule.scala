package mesosphere.marathon.core

import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.plugin.PluginModule
import mesosphere.marathon.core.readiness.ReadinessModule
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.tracker.TaskTrackerModule

/**
  * The exported interface of the [[CoreModuleImpl]].
  *
  * This is necessary to allow guice to introduce proxies to break cyclic dependencies
  * (as long as we have them).
  */
trait CoreModule {
  def leadershipModule: LeadershipModule
  def taskBusModule: TaskBusModule
  def taskJobsModule: TaskJobsModule
  def taskTrackerModule: TaskTrackerModule
  def launcherModule: LauncherModule
  def appOfferMatcherModule: LaunchQueueModule
  def pluginModule: PluginModule
  def authModule: AuthModule
  def readinessModule: ReadinessModule
}
