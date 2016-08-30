package mesosphere.marathon.api

import javax.inject.Inject

import com.twitter.util.NonFatal
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, Identity, UpdateRunSpec }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.{ DeploymentPlan, ScalingProposition }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, UnknownAppException }
import mesosphere.mesos.Constraints
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

  def kill(
    appId: PathId,
    findToKill: (Iterable[Task] => Iterable[Task]),
    wipe: Boolean = false,
    killCount: Integer = -1)(implicit identity: Identity): Future[Iterable[Task]] = {

    result(groupManager.app(appId)) match {
      case Some(app) =>
        checkAuthorization(UpdateRunSpec, app)

        import scala.concurrent.ExecutionContext.Implicits.global
        taskTracker.appTasks(appId).flatMap { allTasks =>
          val foundTasks = getTasksToKill(appId, app, findToKill(allTasks), killCount)
          val expungeTasks = if (wipe) expunge(foundTasks) else Future.successful(())

          expungeTasks.map { _ =>
            val launchedTasks = foundTasks.filter(_.launched.isDefined)
            if (launchedTasks.nonEmpty) {
              service.killTasks(appId, launchedTasks)
              foundTasks
            } else foundTasks
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

  def getTasksToKill(
    appId: PathId,
    appInfo: AppDefinition,
    tasks: Iterable[Task],
    killCount: Integer = -1): Iterable[Task] = {

    def killToMeetConstraints(notSentencedAndRunning: Iterable[Task], toKillCount: Int): Iterable[Task] =
      Constraints.selectTasksToKill(appInfo, notSentencedAndRunning, toKillCount)

    val scaleTo = if (killCount >= 0) tasks.size - killCount else 0

    val prop = ScalingProposition.propose(tasks, None, killToMeetConstraints, scaleTo)
    prop.tasksToKill.getOrElse(Seq.empty[Task])
  }

  def killAndScale(
    appId: PathId,
    findToKill: (Iterable[Task] => Iterable[Task]),
    force: Boolean,
    killCount: Integer = -1)(implicit identity: Identity): Future[DeploymentPlan] = {

    var tasks = findToKill(taskTracker.appTasksLaunchedSync(appId))

    result(groupManager.app(appId)) match {
      case Some(appInfo) =>
        tasks = getTasksToKill(appId, appInfo, tasks, killCount = killCount)
      case None => Future.failed(UnknownAppException(appId))
    }

    killAndScale(Map(appId -> tasks), force)
  }

  def killAndScale(
    appTasks: Map[PathId, Iterable[Task]],
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      checkAuthorization(UpdateRunSpec, app)
      appTasks.get(app.id).fold(app) { toKill => app.copy(instances = app.instances - toKill.size) }
    }

    def updateGroup(group: Group): Group = {
      group.copy(apps = group.apps.mapValues(scaleApp), groups = group.groups.map(updateGroup))
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
