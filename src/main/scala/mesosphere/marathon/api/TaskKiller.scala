package mesosphere.marathon.api

import javax.inject.Inject

import com.twitter.util.NonFatal
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.plugin.auth.{ Identity, UpdateApp, Authenticator, Authorizer }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, UnknownAppException }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

class TaskKiller @Inject() (
    taskTracker: TaskTracker,
    stateOpProcessor: TaskStateOpProcessor,
    groupManager: GroupManager,
    service: MarathonSchedulerService,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends AuthResource {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def kill(appId: PathId,
           findToKill: (Iterable[Task] => Iterable[Task]),
           wipe: Boolean = false)(implicit identity: Identity): Future[Iterable[Task]] = {

    result(groupManager.app(appId)) match {
      case Some(app) =>
        checkAuthorization(UpdateApp, app)

        import scala.concurrent.ExecutionContext.Implicits.global
        taskTracker.appTasks(appId).flatMap { allTasks =>
          val foundTasks = findToKill(allTasks)
          val expungeTasks = if (wipe) expunge(foundTasks) else Future.successful(())

          expungeTasks.map { _ =>
            val launchedTasks = foundTasks.filter(_.launched.isDefined)
            if (launchedTasks.nonEmpty) {
              service.killTasks(appId, launchedTasks)
              foundTasks
            }
            else foundTasks
          }
        }

      case None => Future.failed(UnknownAppException(appId))
    }
  }

  private[this] def expunge(tasks: Iterable[Task])(implicit ec: ExecutionContext): Future[Unit] = {
    tasks.foldLeft(Future.successful(())) { (resultSoFar, nextTask) =>
      resultSoFar.flatMap { _ =>
        log.info("Expunging {}", nextTask.taskId)
        stateOpProcessor.process(TaskStateOp.ForceExpunge(nextTask.taskId)).map(_ => ()).recover {
          case NonFatal(cause) =>
            log.info("Failed to expunge {}, got: {}", Array[Object](nextTask.taskId, cause): _*)
        }
      }
    }
  }

  def killAndScale(appId: PathId,
                   findToKill: (Iterable[Task] => Iterable[Task]),
                   force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    killAndScale(Map(appId -> findToKill(taskTracker.appTasksLaunchedSync(appId))), force)
  }

  def killAndScale(appTasks: Map[PathId, Iterable[Task]],
                   force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      checkAuthorization(UpdateApp, app)
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
