package mesosphere.marathon
package core.appinfo

import java.time.Clock

import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.impl.{ AppInfoBaseData, DefaultInfoService }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.TaskFailureRepository

/**
  * Provides a service to query information related to apps.
  */
class AppInfoModule @Inject() (
    clock: Clock,
    groupManager: GroupManager,
    taskTracker: InstanceTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  private[this] def appInfoBaseData(): AppInfoBaseData = new AppInfoBaseData(
    clock, taskTracker, healthCheckManager, marathonSchedulerService, taskFailureRepository, groupManager)

  def appInfoService: AppInfoService = infoService
  def groupInfoService: GroupInfoService = infoService
  def podStatusService: PodStatusService = infoService

  private[this] lazy val infoService = new DefaultInfoService(groupManager, appInfoBaseData)
}
