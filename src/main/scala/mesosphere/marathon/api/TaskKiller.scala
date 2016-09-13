package mesosphere.marathon.api

import javax.inject.Inject

import com.twitter.util.NonFatal
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, Identity, UpdateRunSpec }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, UnknownAppException }
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

class TaskKiller @Inject() (
    taskTracker: InstanceTracker,
    stateOpProcessor: TaskStateOpProcessor,
    groupManager: GroupManager,
    service: MarathonSchedulerService,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends AuthResource {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def kill(
    appId: PathId,
    findToKill: (Iterable[Instance] => Iterable[Instance]),
    wipe: Boolean = false)(implicit identity: Identity): Future[Iterable[Instance]] = {

    result(groupManager.app(appId)) match {
      case Some(app) =>
        checkAuthorization(UpdateRunSpec, app)

        // TODO: We probably want to pass the execution context as an implcit.
        import scala.concurrent.ExecutionContext.Implicits.global
        async {
          val allTasks = await(taskTracker.specInstances(appId))
          val foundTasks = findToKill(allTasks)
          if (wipe) expunge(foundTasks)
          val launchedTasks = foundTasks.filter(_.isLaunched)
          if (launchedTasks.nonEmpty) await(service.killTasks(appId, launchedTasks))
          // Return killed *and* expunged tasks.
          // The user only cares that all tasks won't exist eventually. That's why we send all tasks back and not just
          // the killed tasks.
          foundTasks
        }

      case None => Future.failed(UnknownAppException(appId))
    }
  }

  private[this] def expunge(tasks: Iterable[Instance])(implicit ec: ExecutionContext): Future[Unit] = {
    // Note: We process all instances sequentially.

    tasks.foldLeft(Future.successful(())) { (resultSoFar, nextInstance) =>
      resultSoFar.flatMap { _ =>
        log.info("Expunging {}", nextInstance.instanceId)
        stateOpProcessor.process(InstanceUpdateOperation.ForceExpunge(nextInstance.instanceId)).map(_ => ()).recover {
          case NonFatal(cause) =>
            log.info("Failed to expunge {}, got: {}", Array[Object](nextInstance.instanceId, cause): _*)
        }
      }
    }
  }

  def killAndScale(
    appId: PathId,
    findToKill: (Iterable[Instance] => Iterable[Instance]),
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    killAndScale(Map(appId -> findToKill(taskTracker.specInstancesLaunchedSync(appId))), force)
  }

  def killAndScale(
    appTasks: Map[PathId, Iterable[Instance]],
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

    appTasks.keys.find(id => !taskTracker.hasSpecInstancesSync(id))
      .map(id => Future.failed(UnknownAppException(id)))
      .getOrElse(killTasks)
  }
}
