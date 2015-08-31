package mesosphere.marathon.core.appinfo

import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.impl.{ AppInfoBaseData, DefaultAppInfoService }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppRepository, GroupManager, TaskFailureRepository }
import mesosphere.marathon.tasks.TaskTracker

/**
  * Provides a service to query information related to apps.
  */
class AppInfoModule @Inject() (
    groupManager: GroupManager,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  private[this] def appInfoBaseData(): AppInfoBaseData =
    new AppInfoBaseData(taskTracker, healthCheckManager, marathonSchedulerService, taskFailureRepository)

  lazy val appInfoService: AppInfoService = new DefaultAppInfoService(groupManager, appRepository, appInfoBaseData)
}
