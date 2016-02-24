package mesosphere.marathon.api

import javax.inject.Inject

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonSchedulerService, UnknownAppException }

import scala.concurrent.Future

class TaskKiller @Inject() (
    taskTracker: TaskTracker,
    groupManager: GroupManager,
    service: MarathonSchedulerService) {

  def kill(
    appId: PathId,
    findToKill: (Iterable[Task] => Iterable[Task])): Future[Iterable[Task]] = {

    val tasks = taskTracker.appTasksLaunchedSync(appId)
    if (tasks.nonEmpty) {
      val toKill = findToKill(tasks)
      service.killTasks(appId, toKill)
      Future.successful(toKill)
    }
    else {
      // this is actually returned when the apps has no tasks
      Future.failed(UnknownAppException(appId))
    }
  }

  def killAndScale(appId: PathId,
                   findToKill: (Iterable[Task] => Iterable[Task]),
                   force: Boolean): Future[DeploymentPlan] = {
    killAndScale(Map(appId -> findToKill(taskTracker.appTasksLaunchedSync(appId))), force)
  }

  def killAndScale(appTasks: Map[PathId, Iterable[Task]], force: Boolean): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      appTasks.get(app.id).fold(app) { toKill => app.copy(instances = app.instances - toKill.size) }
    }
    def updateGroup(group: Group): Group = {
      group.copy(apps = group.apps.map(scaleApp), groups = group.groups.map(updateGroup))
    }
    def killTasks = groupManager.update(
      PathId.empty,
      updateGroup,
      Timestamp.now(),
      force = force,
      toKill = appTasks
    )
    appTasks.keys.find(id => !taskTracker.hasAppTasksSync(id))
      .map(id => Future.failed(UnknownAppException(id)))
      .getOrElse(killTasks)
  }
}
