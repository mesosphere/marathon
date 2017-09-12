package mesosphere.marathon
package core

import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.ActorsModule
import mesosphere.marathon.core.deployment.DeploymentModule
import mesosphere.marathon.core.election.ElectionModule
import mesosphere.marathon.core.event.EventModule
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.core.health.HealthModule
import mesosphere.marathon.core.history.HistoryModule
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.plugin.PluginModule
import mesosphere.marathon.core.pod.PodModule
import mesosphere.marathon.core.readiness.ReadinessModule
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.termination.TaskTerminationModule
import mesosphere.marathon.core.task.tracker.InstanceTrackerModule
import mesosphere.marathon.storage.StorageModule

/**
  * The exported interface of the [[CoreModuleImpl]].
  *
  * This is necessary to allow guice to introduce proxies to break cyclic dependencies
  * (as long as we have them).
  */
trait CoreModule {
  def actorsModule: ActorsModule
  def appOfferMatcherModule: LaunchQueueModule
  def authModule: AuthModule
  def electionModule: ElectionModule
  def eventModule: EventModule
  def groupManagerModule: GroupManagerModule
  def healthModule: HealthModule
  def historyModule: HistoryModule
  def launcherModule: LauncherModule
  def leadershipModule: LeadershipModule
  def pluginModule: PluginModule
  def podModule: PodModule
  def readinessModule: ReadinessModule
  def storageModule: StorageModule
  def taskJobsModule: TaskJobsModule
  def taskTrackerModule: InstanceTrackerModule
  def taskTerminationModule: TaskTerminationModule
  def deploymentModule: DeploymentModule
  def schedulerActions: SchedulerActions
}
