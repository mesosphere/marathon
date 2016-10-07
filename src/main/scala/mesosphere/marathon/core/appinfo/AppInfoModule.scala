package mesosphere.marathon.core.appinfo

import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.impl.{ AppInfoBaseData, DefaultInfoService }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.{ ReadOnlyAppRepository, ReadOnlyPodRepository, TaskFailureRepository }

/**
  * Provides a service to query information related to apps.
  */
class AppInfoModule @Inject() (
    clock: Clock,
    groupManager: GroupManager,
    appRepository: ReadOnlyAppRepository,
    podRepository: ReadOnlyPodRepository,
    taskTracker: InstanceTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  private[this] def appInfoBaseData(): AppInfoBaseData =
    new AppInfoBaseData(clock, taskTracker, healthCheckManager, marathonSchedulerService, taskFailureRepository)

  def appInfoService: AppInfoService = infoService
  def groupInfoService: GroupInfoService = infoService
  def podStatusService: PodStatusService = infoService

  private[this] lazy val infoService = new DefaultInfoService(
    groupManager, appRepository, podRepository, appInfoBaseData)
}
