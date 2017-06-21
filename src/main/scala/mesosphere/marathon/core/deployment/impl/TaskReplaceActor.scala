package mesosphere.marathon
package core.deployment.impl

import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.collection.{ SortedSet, mutable }
import scala.concurrent.Promise

class TaskReplaceActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val killService: KillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with ReadinessBehavior with StrictLogging {
  import TaskReplaceActor._

  // compute all values ====================================================================================

  // All running instances of this app
  //
  // Killed resident tasks are not expunged from the instances list. Ignore
  // them. LaunchQueue takes care of launching instances against reservations
  // first
  val currentRunningInstances = instanceTracker.specInstancesSync(runSpec.id).filter(_.isActive)

  // In case previous master was abdicated while the deployment was still running we might have
  // already started some new tasks.
  // All already started and active tasks are filtered while the rest is considered
  private[this] val (instancesAlreadyStarted, instancesToKill) = {
    currentRunningInstances.partition(_.runSpecVersion == runSpec.version)
  }

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, currentRunningInstances.size)

  // compute all variables maintained in this actor =========================================================

  // All instances to kill queued up
  private[this] val toKill: mutable.Queue[Instance] = instancesToKill.to[mutable.Queue]

  // All instances to kill as set for quick lookup
  private[this] var oldInstanceIds: SortedSet[Id] = instancesToKill.map(_.instanceId).to[SortedSet]

  // The number of started instances. Defaults to the number of already started instances.
  var instancesStarted: Int = instancesAlreadyStarted.size

  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // reconcile the state from a possible previous run
    reconcileAlreadyStartedInstances()

    // kill old instances to free some capacity
    for (_ <- 0 until ignitionStrategy.nrToKillImmediately) killNextOldInstance()

    // start new instances, if possible
    launchInstances()

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)

    // it might be possible, that we come here, but nothing is left to do
    checkFinished()
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = readinessBehavior orElse replaceBehavior

  def replaceBehavior: Receive = {
    // New instance failed to start, restart it
    case InstanceChanged(id, `version`, `pathId`, condition, instance) if !oldInstanceIds(id) && considerTerminal(condition) =>
      logger.error(s"New instance $id failed on agent ${instance.agentInfo.agentId} during app $pathId restart")
      instanceTerminated(id)
      instancesStarted -= 1
      launchInstances()

    // Old instance successfully killed
    case InstanceChanged(id, _, `pathId`, condition, _) if oldInstanceIds(id) && considerTerminal(condition) =>
      oldInstanceIds -= id
      launchInstances()
      checkFinished()

    // Ignore change events, that are not handled in parent receives
    case _: InstanceChanged =>

  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    if (healthyInstances(instanceId) && readyInstances(instanceId)) killNextOldInstance(Some(instanceId))
    checkFinished()
  }

  def reconcileAlreadyStartedInstances(): Unit = {
    logger.info(s"reconcile: found ${instancesAlreadyStarted.size} already started instances " +
      s"and ${oldInstanceIds.size} old instances")
    instancesAlreadyStarted.foreach(reconcileHealthAndReadinessCheck)
  }

  def launchInstances(): Unit = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstanceIds.size - instancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - instancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      logger.info(s"Reconciling instances during app $pathId restart: queuing $instancesToStartNow new instances")
      launchQueue.add(runSpec, instancesToStartNow)
      instancesStarted += instancesToStartNow
    }
  }

  def killNextOldInstance(maybeNewInstanceId: Option[Instance.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val nextOldInstance = toKill.dequeue()

      maybeNewInstanceId match {
        case Some(newInstanceId: Instance.Id) =>
          logger.info(s"Killing old ${nextOldInstance.instanceId} because $newInstanceId became reachable")
        case _ =>
          logger.info(s"Killing old ${nextOldInstance.instanceId}")
      }

      killService.killInstance(nextOldInstance, KillReason.Upgrading)
    }
  }

  def checkFinished(): Unit = {
    if (targetCountReached(runSpec.instances) && oldInstanceIds.isEmpty) {
      logger.info(s"All new instances for $pathId are ready and all old instances have been killed")
      promise.success(())
      context.stop(self)
    } else {
      logger.debug(s"For run spec: [${runSpec.id}] there are [${healthyInstances.size}] healthy and " +
        s"[${readyInstances.size}] ready new instances and " +
        s"[${oldInstanceIds.size}] old instances.")
    }
  }
}

object TaskReplaceActor extends StrictLogging {

  //scalastyle:off
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    killService: KillService,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManager, status, killService, launchQueue, instanceTracker, eventBus,
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
      if (runSpec.residency.isDefined) {
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

