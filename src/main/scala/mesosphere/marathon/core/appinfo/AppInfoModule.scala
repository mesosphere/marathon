package mesosphere.marathon.core.appinfo

import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.impl.{ AppInfoBaseData, DefaultInfoService }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppRepository, GroupManager, TaskFailureRepository }

/**
  * Provides a service to query information related to apps.
  */
class AppInfoModule @Inject() (
    clock: Clock,
    groupManager: GroupManager,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  private[this] def appInfoBaseData(): AppInfoBaseData =
    new AppInfoBaseData(clock, taskTracker, healthCheckManager, marathonSchedulerService, taskFailureRepository)

  def appInfoService: AppInfoService = infoService
  def groupInfoService: GroupInfoService = infoService

  private[this] lazy val infoService = new DefaultInfoService(groupManager, appRepository, appInfoBaseData)
}
