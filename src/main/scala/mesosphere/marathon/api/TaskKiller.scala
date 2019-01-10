package mesosphere.marathon
package api

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Inject
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.task.termination.impl.KillStreamWatcher
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, Identity, UpdateRunSpec}
import mesosphere.marathon.state._

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class TaskKiller @Inject() (
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    killService: KillService)(implicit val executionContext: ExecutionContext, implicit val materializer: Materializer) extends AuthResource with StrictLogging {

  def kill(
    runSpecId: PathId,
    findToKill: (Seq[Instance] => Seq[Instance]),
    wipe: Boolean = false)(implicit identity: Identity): Future[Seq[Instance]] = {

    groupManager.runSpec(runSpecId) match {
      case Some(runSpec) =>
        checkAuthorization(UpdateRunSpec, runSpec)
        async { // linter:ignore:UnnecessaryElseBranch
          val allInstances = await(instanceTracker.specInstances(runSpecId))
          val foundInstances = findToKill(allInstances)
          val activeInstances = foundInstances.filter(_.isActive)

          if (wipe) {
            val instancesAreTerminal: Future[Done] = KillStreamWatcher.watchForDecommissionedInstances(
              instanceTracker.instanceUpdates, activeInstances)
            await(Future.sequence(foundInstances.map(i => instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalChangeReason.UserRequest)))): @silent
            await(doForceExpunge(foundInstances.map(_.instanceId))): @silent
            await(instancesAreTerminal): @silent
          } else {
            if (activeInstances.nonEmpty) {
              // This is legit. We don't adjust the goal, since that should stay whatever it is.
              // However we kill the tasks associated with these instances directly via the killService.
              killService.killInstancesAndForget(activeInstances, KillReason.KillingTasksViaApi)
            }
          }: @silent
          // Return killed *and* expunged instances.
          // The user only cares that all instances won't exist eventually. That's why we send all instances back and
          // not just the killed instances.
          foundInstances
        }

      case None => Future.failed(PathNotFoundException(runSpecId))
    }
  }

  private[this] def doForceExpunge(instances: Iterable[Instance.Id]): Future[Done] = {
    // Note: We process all instances sequentially.

    instances.foldLeft(Future.successful(Done)) { (resultSoFar, nextInstanceId) =>
      resultSoFar.flatMap { _ =>
        logger.info(s"Expunging ${nextInstanceId}")
        instanceTracker.forceExpunge(nextInstanceId)
          .recover {
            case NonFatal(cause) =>
              logger.warn(s"Failed to expunge ${nextInstanceId}, got:", cause)
              Done
          }.map(_ => Done)
      }
    }
  }

  def killAndScale(
    appId: PathId,
    findToKill: (Seq[Instance] => Seq[Instance]),
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = async {
    val instances = await(instanceTracker.specInstances(appId))
    val activeInstances = instances.filter(_.isActive)
    val instancesToKill = findToKill(activeInstances)
    await(killAndScale(Map(appId -> instancesToKill), force))
  }

  def killAndScale(
    appInstances: Map[PathId, Seq[Instance]],
    force: Boolean)(implicit identity: Identity): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      checkAuthorization(UpdateRunSpec, app)
      appInstances.get(app.id).fold(app) { instances =>
        // only count active instances that did not already receive a kill request.
        val toKillCount = instances.count(i => i.isActive && !i.isKilling)
        // make sure we never scale below zero instances.
        app.copy(instances = math.max(0, app.instances - toKillCount))
      }
    }

    val version = Timestamp.now()

    def killDeployment = groupManager.updateRoot(
      PathId.empty,
      _.updateTransitiveApps(PathId.empty, scaleApp, version),
      version = version,
      force = force,
      toKill = appInstances
    )

    async {
      val allInstances = await(instanceTracker.instancesBySpec()).instancesMap
      //TODO: The exception does not take multiple ids.
      appInstances.keys.find(!allInstances.contains(_)).map(id => throw PathNotFoundException(id))
      await(killDeployment)
    }
  }
}
