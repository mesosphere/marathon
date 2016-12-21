package mesosphere.marathon
package api

import javax.inject.Inject

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, Identity, UpdateRunSpec }
import mesosphere.marathon.state._
import mesosphere.marathon.core.deployment.DeploymentPlan
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

class TaskKiller @Inject() (
    instanceTracker: InstanceTracker,
    stateOpProcessor: TaskStateOpProcessor,
    groupManager: GroupManager,
    service: MarathonSchedulerService,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer) extends AuthResource {

  private[this] val log = LoggerFactory.getLogger(getClass)

  @SuppressWarnings(Array("all")) // async/await
  def kill(
    runSpecId: PathId,
    findToKill: (Seq[Instance] => Seq[Instance]),
    wipe: Boolean = false)(implicit identity: Identity): Future[Seq[Instance]] = {

    result(groupManager.runSpec(runSpecId)) match {
      case Some(runSpec) =>
        checkAuthorization(UpdateRunSpec, runSpec)
        async { // linter:ignore:UnnecessaryElseBranch
          val allTasks = await(instanceTracker.specInstances(runSpecId))
          val foundTasks = findToKill(allTasks)

          if (wipe) await(expunge(foundTasks))

          val launchedTasks = foundTasks.filter(_.isLaunched)
          if (launchedTasks.nonEmpty) service.killTasks(runSpecId, launchedTasks)
          // Return killed *and* expunged tasks.
          // The user only cares that all tasks won't exist eventually. That's why we send all tasks back and not just
          // the killed tasks.
          foundTasks
        }

      case None => Future.failed(PathNotFoundException(runSpecId))
    }
  }

  private[this] def expunge(tasks: Seq[Instance]): Future[Unit] = {
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

  @SuppressWarnings(Array("all")) // async/await
  def killAndScale(
    appId: PathId,
    findToKill: (Seq[Instance] => Seq[Instance]),
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = async {
    val instances = await(instanceTracker.specInstances(appId))
    val launchedInstances = instances.filter(_.isLaunched)
    val instancesToKill = findToKill(launchedInstances)
    await(killAndScale(Map(appId -> instancesToKill), force))
  }

  @SuppressWarnings(Array("all")) // async/await
  def killAndScale(
    appTasks: Map[PathId, Seq[Instance]],
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      checkAuthorization(UpdateRunSpec, app)
      appTasks.get(app.id).fold(app) { tasks =>
        // only count active tasks that did not already receive a kill request.
        val toKillCount = tasks.count(i => i.isActive && !i.isKilling)
        // make sure we never scale below zero instances.
        app.copy(instances = math.max(0, app.instances - toKillCount))
      }
    }

    val version = Timestamp.now()

    def killTasks = groupManager.updateRoot(
      _.updateTransitiveApps(PathId.empty, scaleApp, version),
      version = version,
      force = force,
      toKill = appTasks
    )

    async {
      val allInstances = await(instanceTracker.instancesBySpec()).instancesMap
      //TODO: The exception does not take multiple ids.
      appTasks.keys.find(!allInstances.contains(_)).map(id => throw PathNotFoundException(id))
      await(killTasks)
    }
  }
}
