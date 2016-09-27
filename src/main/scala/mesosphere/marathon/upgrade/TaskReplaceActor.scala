package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.InstanceStatus.Terminal
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.upgrade.TaskReplaceActor._
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.{ SortedSet, mutable }
import scala.concurrent.Promise

class TaskReplaceActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val driver: SchedulerDriver,
    val killService: KillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with ReadinessBehavior with ActorLogging {

  val instancesToKill = instanceTracker.specInstancesLaunchedSync(runSpec.id)
  var newInstancesStarted: Int = 0
  var oldInstanceIds = instancesToKill.map(_.instanceId).to[SortedSet]
  val toKill = instancesToKill.to[mutable.Queue]
  var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
  var outstandingKills = Set.empty[Instance.Id]

  override def preStart(): Unit = {
    super.preStart()
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    val ignitionStrategy = computeRestartStrategy(runSpec, instancesToKill.size)
    maxCapacity = ignitionStrategy.maxCapacity

    for (_ <- 0 until ignitionStrategy.nrToKillImmediately) {
      killNextOldInstance()
    }

    reconcileNewInstances()

    log.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
    super.postStop()
  }

  override def receive: Receive = readinessBehavior orElse replaceBehavior

  def replaceBehavior: Receive = {
    // New instance failed to start, restart it
    case InstanceChanged(id, `version`, `pathId`, Terminal(_), instance) if !oldInstanceIds(id) =>
      log.error(s"New instance $id failed on agent ${instance.agentInfo.agentId} during app $pathId restart")
      instanceTerminated(id)
      launchQueue.add(runSpec)

    // Old instance successfully killed
    case InstanceChanged(id, _, `pathId`, Terminal(_), instance) if oldInstanceIds(id) =>
      oldInstanceIds -= id
      outstandingKills -= id
      reconcileNewInstances()
      checkFinished()

    case change: InstanceChanged => //ignore
  }

  override def instanceStatusChanged(instanceId: Instance.Id): Unit = {
    if (healthyInstances(instanceId) && readyInstances(instanceId)) killNextOldInstance(Some(instanceId))
    checkFinished()
  }

  def reconcileNewInstances(): Unit = {
    val leftCapacity = math.max(0, maxCapacity - oldInstanceIds.size - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      log.info(s"Reconciling instances during app $pathId restart: queuing $instancesToStartNow new instances")
      launchQueue.add(runSpec, instancesToStartNow)
      newInstancesStarted += instancesToStartNow
    }
  }

  def killNextOldInstance(maybeNewInstanceId: Option[Instance.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val nextOldInstance = toKill.dequeue()

      maybeNewInstanceId match {
        case Some(newInstanceId: Instance.Id) =>
          log.info(s"Killing old ${nextOldInstance.instanceId} because $newInstanceId became reachable")
        case _ =>
          log.info(s"Killing old ${nextOldInstance.instanceId}")
      }

      outstandingKills += nextOldInstance.instanceId
      killService.killInstance(nextOldInstance, KillReason.Upgrading)
    }
  }

  def checkFinished(): Unit = {
    if (targetCountReached(runSpec.instances) && oldInstanceIds.isEmpty) {
      log.info(s"All new instances for $pathId are ready and all old instances have been killed")
      promise.success(())
      context.stop(self)
    } else if (log.isDebugEnabled) {
      log.debug(s"For run spec: [${runSpec.id}] there are [${healthyInstances.size}] healthy and " +
        s"[${readyInstances.size}] ready new instances and " +
        s"[${oldInstanceIds.size}] old instances.")
    }
  }
}

object TaskReplaceActor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  //scalastyle:off
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    killService: KillService,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManager, status, driver, killService, launchQueue, instanceTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[upgrade] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  // TODO (pods): this function should probably match against the type?
  private[upgrade] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): RestartStrategy = {
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
        log.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        log.info("maxCapacity == minHealthy: Allow temporary over-capacity of one instance to allow restarting")
        maxCapacity += 1
      }
    }

    log.info(s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
      s"$minHealthy instances running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningInstancesCount running instances immediately")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewInstances: Boolean = minHealthy < maxCapacity || runningInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewInstances, "must be able to start new instances")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}

