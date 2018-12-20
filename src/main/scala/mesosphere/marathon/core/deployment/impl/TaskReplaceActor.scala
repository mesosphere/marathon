package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}

import scala.async.Async.{async, await}
import scala.collection.{SortedSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val killService: KillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with StrictLogging {
  import TaskReplaceActor._

  val pathId: PathId = runSpec.id

  // All running instances of this app
  val instances: mutable.Map[Instance.Id, Instance] = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut)
  val instancesHealth: mutable.Map[Instance.Id, Boolean] = instances.collect {
    case (id, instance) => id -> instance.state.healthy.getOrElse(false)
  }

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // kill old instances to free some capacity
    self ! KillImmediately(ignitionStrategy.nrToKillImmediately)

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = killing

  // Commands
  case object Check
  case object KillNext
  case class KillImmediately(oldInstances: Int)
  case class Killed(id: Seq[Instance.Id])
  case object LaunchNext

  // Phases

  def updating: Receive = {
    case InstanceChanged(id, _, _, _, instance) =>
      instances += id -> instance
      context.become(checking)
      self ! Check
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      instancesHealth += id -> healthy.getOrElse(false)
  }

  // Check if we are done.
  def checking: Receive = {
    case Check =>
      // Are all old instances terminal?
      val oldTerminal = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).forall { instance =>
        considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
      }

      // Are all new instances running and healthy?
      val newActive = instances.valuesIterator.count { instance =>
        val healthy = if (hasHealthChecks) instancesHealth.getOrElse(instance.instanceId, false) else true
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && healthy
      }

      // Are all new instances ready?
      // TODO

      if (oldTerminal && newActive == runSpec.instances) {
        logger.info(s"All new instances for $pathId are ready and all old instances have been killed")
        promise.trySuccess(())
        context.stop(self)
      } else {
        context.become(killing)
        self ! KillNext
      }

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  // Kill next old instance
  def killing: Receive = {
    case KillImmediately(oldInstances) =>
      instances.valuesIterator
        .filter { instance =>
          instance.runSpecVersion < runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running
        }
        .take(oldInstances)
        .foldLeft(Future.successful(Seq.empty[Instance.Id])) { (acc, nextDoomed) =>
          async {
            val current = await(acc)
            await(killNextOldInstance(nextDoomed))
            current :+ nextDoomed.instanceId
          }
        }.map(Killed).pipeTo(self)

    case KillNext =>
      // Pick first active old instance that is active and has goal running
      instances.valuesIterator.find { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running
      } match {
        case Some(doomed) => killNextOldInstance(doomed).map(_ => Killed(Seq(doomed.instanceId))).pipeTo(self)
        case None => self ! Killed(Seq.empty)
      }

    case Killed(killIds) =>
      // TODO(karsten): We may want to wait for `InstanceChanged(instanceId, ..., Goal.Stopped | Goal.Decommissioned)`.
      // We mark the instance as doomed so that we won't select it in the next run.
      killIds.foreach { instanceId =>
        val killedInstance = instances(instanceId)
        val updatedState = killedInstance.state.copy(goal = Goal.Stopped)
        instances += instanceId -> killedInstance.copy(state = updatedState)
      }

      context.become(launching)
      self ! LaunchNext

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  // Launch next new instance
  def launching: Receive = {
    case LaunchNext =>
      val oldActiveInstances = instances.valuesIterator.count { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running
      }
      val newInstancesStarted = instances.valuesIterator.count { instance =>
        instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
      }
      launchInstances(oldActiveInstances, newInstancesStarted).pipeTo(self)

    case scheduledInstances: Seq[Instance] =>
      // We take note of all scheduled instances before accepting new updates so that we do not overscale.
      scheduledInstances.foreach { instance =>
        // The launch queue actor does not change instances so we have to ensure that the goal is running.
        // These instance will be overridden by new updates but for now we just need to know that we scheduled them.
        val updatedState = instance.state.copy(goal = Goal.Running)
        instances += instance.instanceId -> instance.copy(state = updatedState)
      }
      context.become(updating)

      // We went through all phases so lets unleash all pending instance changed updates.
      unstashAll()

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  // Careful not to make this method completely asynchronous - it changes local actor's state `instancesStarted`.
  // Only launching new instances needs to be asynchronous.
  def launchInstances(oldActiveInstances: Int, newInstancesStarted: Int): Future[Seq[Instance]] = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldActiveInstances - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      logger.info(s"Restarting app $pathId: queuing $instancesToStartNow new instances")
      launchQueue.addWithReply(runSpec, instancesToStartNow)
    } else {
      Future.successful(Seq.empty)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def killNextOldInstance(dequeued: Instance): Future[Done] = {
    async {
      await(instanceTracker.get(dequeued.instanceId)) match {
        case None =>
          logger.warn(s"Was about to kill instance ${dequeued} but it did not exist in the instance tracker anymore.")
          Done
        case Some(nextOldInstance) =>
          logger.info(s"Killing old ${nextOldInstance.instanceId}")

          if (runSpec.isResident) {
            await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Stopped))
          } else {
            await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Decommissioned))
          }
          await(killService.killInstance(nextOldInstance, KillReason.Upgrading))
      }
    }
  }

  protected val hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }
}

object TaskReplaceActor extends StrictLogging {

  object CheckFinished

  //scalastyle:off
  def props(
    deploymentManagerActor: ActorRef,
    status: DeploymentStatus,
    killService: KillService,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManagerActor, status, killService, launchQueue, instanceTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[impl] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  private[impl] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): RestartStrategy = {
    // in addition to a spec which passed validation, we require:
    require(runSpec.instances > 0, s"instances must be > 0 but is ${runSpec.instances}")
    require(runningInstancesCount >= 0, s"running instances count must be >=0 but is $runningInstancesCount")

    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, runningInstancesCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= runningInstancesCount) {
      if (runSpec.isResident) {
        // Kill enough instances so that we end up with one instance below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = runningInstancesCount - minHealthy + 1
        logger.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        logger.info("maxCapacity == minHealthy: Allow temporary over-capacity of one instance to allow restarting")
        maxCapacity += 1
      }
    }

    logger.info(s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
      s"$minHealthy instances running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningInstancesCount running instances immediately. (RunSpec version ${runSpec.version})")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewInstances: Boolean = minHealthy < maxCapacity || runningInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewInstances, "must be able to start new instances")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}

