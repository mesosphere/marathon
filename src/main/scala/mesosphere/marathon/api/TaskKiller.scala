package mesosphere.marathon.api

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ AppDefinition, GroupManager, PathId, Timestamp }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonSchedulerService, UnknownAppException }

import scala.concurrent.Future

class TaskKiller @Inject() (
    taskTracker: TaskTracker,
    groupManager: GroupManager,
    service: MarathonSchedulerService) {

  def kill(appId: PathId,
           findToKill: (Set[MarathonTask] => Set[MarathonTask]),
           force: Boolean): Future[Set[MarathonTask]] = {

    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val toKill = findToKill(tasks)
      service.killTasks(appId, toKill)
      Future.successful(toKill)
    }
    else {
      Future.failed(UnknownAppException(appId))
    }
  }

  def killAndScale(appId: PathId,
                   findToKill: (Set[MarathonTask] => Set[MarathonTask]),
                   force: Boolean): Future[DeploymentPlan] = {

    if (taskTracker.contains(appId)) {
      val tasks = taskTracker.get(appId)
      val toKill = findToKill(tasks)
      def scaleExisting(opt: Option[AppDefinition]): AppDefinition = opt
        .map(app => app.copy(instances = app.instances - toKill.size))
        .getOrElse(throw new UnknownAppException(appId))

      groupManager.updateApp(appId, scaleExisting, Timestamp.now(), force = force, toKill = toKill)
    }
    else {
      Future.failed(UnknownAppException(appId))
    }
  }

}
