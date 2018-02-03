package mesosphere.marathon
package core.appinfo

import java.time.Clock

import com.google.inject.Inject
import mesosphere.marathon.core.appinfo.impl.{ AppInfoBaseData, DefaultInfoService }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.TaskFailureRepository
import mesosphere.util.NamedExecutionContext

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

  val ec = NamedExecutionContext.fixedThreadPoolExecutionContext(8, "app-info-module")

  private[this] val appInfoBaseData = () => new AppInfoBaseData(
    clock, taskTracker, healthCheckManager, marathonSchedulerService, taskFailureRepository, groupManager)(ec)

  def appInfoService: AppInfoService = infoService
  def groupInfoService: GroupInfoService = infoService
  def podStatusService: PodStatusService = infoService

  private[this] lazy val infoService = new DefaultInfoService(groupManager, appInfoBaseData)
}
