package mesosphere.marathon
package core.deployment.impl

import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.GoalChangeReason.Upgrading
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.concurrent.Promise

trait ComputeRestartStrategy extends StrictLogging {

  private[impl] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): TaskReplaceActor.RestartStrategy = {
    // in addition to a spec which passed validation, we require:
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

    TaskReplaceActor.RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}

/**
  * The [[TaskReplaceBehaviour]] defines the business logic for replacing instances (tasks).
  */
trait TaskReplaceBehaviour extends FrameProcessor with ComputeRestartStrategy with StrictLogging { this: BaseReadinessScheduling =>

  // The ignition strategy for this run specification
  val ignitionStrategy: TaskReplaceActor.RestartStrategy

  /**
    * A frame process goes through the following phases:
    *
    * 1. `checking` Check if all old instances are terminal and all new instances are running, ready and healthy.
    * 1. `killing` Kill the next old instance, ie set the goal and update our internal state. We are ahead of what has been persisted to ZooKeeper.
    * 1. `launching` Scheduler one readiness check for a healthy new instance and launch a new instance if required.
    */
  def process(completedPhases: Int, frame: Frame): FrameProcessor.ProcessResult = {
    if (check(frame)) {
      FrameProcessor.Stop
    } else {
      val frameAfterKilling = if (completedPhases == 0) {
        killImmediately(ignitionStrategy.nrToKillImmediately, frame)
      } else {
        killNext(frame)
      }
      val nextFrame = launching(frameAfterKilling)
      FrameProcessor.Continue(nextFrame)
    }
  }

  // Check if we are done.
  def check(frame: Frame): Boolean = {
    val instances = frame.instances
    val readableInstances = instances.values.map(readableInstanceString).mkString(",")
    logPrefixedInfo("checking")(s"Checking if we are done with new version ${runSpec.version} for $readableInstances")
    // Are all old instances terminal?
    val oldTerminalInstances = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).count { instance =>
      considerTerminal(instance.state.condition) && instance.state.goal.isTerminal()
    }

    val oldActiveInstances = instances.valuesIterator.count(_.runSpecVersion < runSpec.version) - oldTerminalInstances
    val allOldTerminal: Boolean = oldActiveInstances == 0

    // Are all new instances running, ready and healthy?
    val newActive = instances.valuesIterator.count { instance =>
      val healthy = if (runSpec.hasHealthChecks) frame.instancesHealth.getOrElse(instance.instanceId, false) else true
      val ready = if (runSpec.hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
      instance.runSpecVersion == runSpec.version && instance.state.condition == Condition.Running && instance.state.goal == Goal.Running && healthy && ready
    }

    val newReady = instances.valuesIterator.count { instance =>
      if (runSpec.hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
    }

    val newStaged = instances.valuesIterator.count { instance =>
      instance.runSpecVersion == runSpec.version && instance.isScheduled && instance.state.goal == Goal.Running
    }

    val summary = s"$oldActiveInstances old active, $oldTerminalInstances old terminal, $newActive new active, $newStaged new scheduled, $newReady new ready"
    if (allOldTerminal && newActive == runSpec.instances) {
      logPrefixedInfo("checking")(s"Done for $pathId: $summary")
      true
    } else {
      logPrefixedInfo("checking")(s"Not done yet: $summary")
      false
    }
  }

  /**
    * Kill instances immediately. This is called only once during the initialization, ie [[UpdateBehaviour.completedPhases]] == 0.
    *
    * @param oldInstances The number of instances with an older version to kill immediately.
    * @param frame        The [[UpdateBehaviour.currentFrame]].
    * @return An updated [[Frame]] with changed goals for old instances.
    */
  def killImmediately(oldInstances: Int, frame: Frame): Frame = {
    logPrefixedInfo("killing")(s"Killing $oldInstances immediately.")
    frame.instances.valuesIterator
      .filter { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      }
      .take(oldInstances)
      .foldLeft(frame) { (currentFrame, nextDoomed) =>
        val instanceId = nextDoomed.instanceId
        val newGoal = if (runSpec.isResident) Goal.Stopped else Goal.Decommissioned
        logPrefixedInfo("killing")(s"adjusting $instanceId to goal $newGoal ($Upgrading)")
        frame.setGoal(instanceId, newGoal)
      }
  }

  /**
    * Kill the next old instances. This is called for all [[UpdateBehaviour.completedPhases]] > 0.
    *
    * @param frame The [[UpdateBehaviour.currentFrame]].
    * @return An updated [[Frame]] with changed goals for old instances doomed to be killed.
    */
  def killNext(frame: Frame): Frame = {
    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val enoughHealthy = if (runSpec.hasHealthChecks) frame.instancesHealth.valuesIterator.count(_ == true) >= minHealthy else true
    val allNewReady = if (runSpec.hasReadinessChecks) {
      frame.instances.valuesIterator.filter(_.runSpecVersion == runSpec.version).forall { newInstance =>
        frame.instancesReady.getOrElse(newInstance.instanceId, false)
      }
    } else true
    val shouldKill = enoughHealthy && allNewReady

    if (shouldKill) {
      logPrefixedInfo("killing")("Picking next old instance.")
      // Pick first active old instance that has goal running
      frame.instances.valuesIterator.find { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      } match {
        case Some(Instance(instanceId, _, _, _, _, _)) =>
          val newGoal = if (runSpec.isResident) Goal.Stopped else Goal.Decommissioned
          logPrefixedInfo("killing")(s"adjusting $instanceId to goal $newGoal ($Upgrading)")
          frame.setGoal(instanceId, newGoal)
        case None =>
          logPrefixedInfo("killing")("No next instance to kill.")
          frame
      }
    } else {
      val currentHealthyInstances = frame.instancesHealth.valuesIterator.count(_ == true)
      logPrefixedInfo("killing")(s"Not killing next instance because $currentHealthyInstances healthy but minimum $minHealthy required.")
      frame
    }
  }

  /**
    * Called after [[killImmediately()]] or [[killNext()]] phases. It starts readiness checks and schedules or reschedules
    * instances with a new [[runSpec.version]].
    *
    * @param frame The [[Frame]] returned by [[killImmediately()]] or [[killNext()]]. It includes goal changes.
    * @return An updated [[Frame]] with new scheduled or rescheduled instances.
    */
  def launching(frame: Frame): Frame = {

    // Schedule readiness check for new healthy instance that has no scheduled check yet.
    val frameWithReadiness: Frame = scheduleReadinessCheck(frame)

    logPrefixedInfo("launching")("Launching next instance")
    val instances = frameWithReadiness.instances
    val oldTerminalInstances = instances.valuesIterator.count { instance =>
      instance.runSpecVersion < runSpec.version && considerTerminal(instance.state.condition) && instance.state.goal.isTerminal()
    }
    val oldInstances = instances.valuesIterator.count(_.runSpecVersion < runSpec.version) - oldTerminalInstances

    val newInstancesStarted = instances.valuesIterator.count { instance =>
      instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
    }
    logPrefixedInfo("launching")(s"with $oldTerminalInstances old terminal, $oldInstances old active and $newInstancesStarted new started instances.")
    launchInstances(oldInstances, newInstancesStarted, frameWithReadiness)
  }

  /**
    * Called during [[launching()]] phase. This method actually modifies the frame with scheduled or rescheduled instances.
    *
    * @param oldInstances Number of active instances with an older run spec version.
    * @param newInstancesStarted Number of active or scheduled instances with new [[runSpec.version]].
    * @param frame The frame updated by [[killImmediately()]], [[killNext()]] or [[scheduleReadinessCheck()]].
    * @return An updated [[Frame]].
    */
  def launchInstances(oldInstances: Int, newInstancesStarted: Int, frame: Frame): Frame = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstances - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)

    if (instancesToStartNow > 0) {
      logPrefixedInfo("launching")(s"Queuing $instancesToStartNow new instances")
      frame.add(runSpec, instancesToStartNow)
    } else {
      logPrefixedInfo("launching")(s"Not queuing new instances: $instancesNotStartedYet not stared, $leftCapacity capacity left")
      frame
    }
  }
}

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val promise: Promise[Unit]) extends Actor with TaskReplaceBehaviour with UpdateBehaviour with StrictLogging {

  // All running instances of this app
  var currentFrame = Frame(instanceTracker.specInstancesSync(runSpec.id))

  // The ignition strategy for this run specification
  override val ignitionStrategy = computeRestartStrategy(runSpec, currentFrame.instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    if (runSpec.hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    // reconcile the state from a possible previous run
    currentFrame = reconcileAlreadyStartedInstances(currentFrame)

    // Start processing and kill old instances to free some capacity
    self ! FrameProcessor.Process

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }
}

object TaskReplaceActor extends StrictLogging {

  //scalastyle:off
  def props(
    deploymentManagerActor: ActorRef,
    status: DeploymentStatus,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManagerActor, status, launchQueue, instanceTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[impl] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)
}

