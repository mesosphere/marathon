package mesosphere.marathon.api

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
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
    findToKill: (Iterable[MarathonTask] => Iterable[MarathonTask])): Future[Iterable[MarathonTask]] = {

    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.getTasks(appId)
      val toKill = findToKill(tasks)
      service.killTasks(appId, toKill)
      Future.successful(toKill)
    }
    else {
      Future.failed(UnknownAppException(appId))
    }
  }

  def killAndScale(appId: PathId,
                   findToKill: (Iterable[MarathonTask] => Iterable[MarathonTask]),
                   force: Boolean): Future[DeploymentPlan] = {
    killAndScale(Map(appId -> findToKill(taskTracker.getTasks(appId))), force)
  }

  def killAndScale(appTasks: Map[PathId, Iterable[MarathonTask]], force: Boolean): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      appTasks.get(app.id).fold(app) { toKill => app.copy(instances = app.instances - toKill.size) }
    }
    def updateGroup(group: Group): Group = {
      group.copy(apps = group.apps.map(scaleApp), groups = group.groups.map(updateGroup))
    }
    def killTasks = groupManager.update(PathId.empty, updateGroup, Timestamp.now(), force = force, toKill = appTasks)
    appTasks.keys.find(id => !taskTracker.contains(id))
      .map(id => Future.failed(UnknownAppException(id)))
      .getOrElse(killTasks)
  }
}
