package mesosphere.marathon
package api

import javax.inject.Inject

import akka.Done
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.ExecutionContext
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.{Goal, GoalAdjustmentReason, Instance}
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, Identity, UpdateRunSpec}
import mesosphere.marathon.state._

import scala.async.Async.{async, await}
import scala.concurrent.Future
import scala.util.control.NonFatal

class TaskKiller @Inject() (
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    service: MarathonSchedulerService,
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    killService: KillService)(implicit val executionContext: ExecutionContext) extends AuthResource with StrictLogging {

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
            val instancesAreTerminal = killService.watchForKilledInstances(activeInstances)
            val setGoalFutures = foundInstances.map(i => instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalAdjustmentReason.UserRequest))
            await(Future.sequence(setGoalFutures)): @silent
            // TODO: it's not clear yet whether expunging them explicitly is needed. Setting goal to Decommissioned
            // should suffice: the tasks are killed and reservations/volumes can be destroyed BEFORE the instance
            // is expunged. We can still expunge them if they didn't turn terminal after some time (i.e. when Mesos
            // doesn't send  terminal status updates for some reason, e.g. DCOS-42666)
            await(expunge(foundInstances)): @silent
            await(instancesAreTerminal)
          } else {
            if (activeInstances.nonEmpty) {
              // This is legit. We don't adjust the goal, since that should stay whatever it is.
              // However we kill the tasks associated with these instances directly via the killService.
              await(killService.killInstances(activeInstances, KillReason.KillingTasksViaApi))
            }
          }
          // Return killed *and* expunged instances.
          // The user only cares that all instances won't exist eventually. That's why we send all instances back and
          // not just the killed instances.
          foundInstances
        }

      case None => Future.failed(PathNotFoundException(runSpecId))
    }
  }

  private[this] def expunge(instances: Seq[Instance]): Future[Done] = {
    // Note: We process all instances sequentially.

    instances.foldLeft(Future.successful(Done)) { (resultSoFar, nextInstance) =>
      resultSoFar.flatMap { _ =>
        logger.info(s"Expunging ${nextInstance.instanceId}")
        instanceTracker.forceExpunge(nextInstance.instanceId)
          .recover {
            case NonFatal(cause) =>
              logger.warn(s"Failed to expunge ${nextInstance.instanceId}, got:", cause)
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
