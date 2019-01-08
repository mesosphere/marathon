package mesosphere.marathon
package core.deployment.impl

import akka.actor._
import akka.event.EventStream
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.ReadinessCheckUpdate
import mesosphere.marathon.core.deployment.impl.ReadinessBehavior.{ReadinessCheckStreamDone, ReadinessCheckSubscriptionKey}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.GoalChangeReason.Upgrading
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with StrictLogging {
  import TaskReplaceActor._

  val pathId: PathId = runSpec.id

  // All running instances of this app
  var currentFrame = Frame(instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))
  var completedPhases: Int = 0

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, currentFrame.instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // reconcile the state from a possible previous run
    reconcileAlreadyStartedInstances()

    // Start processing and kill old instances to free some capacity
    self ! Process

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = processing

  // Commands
  case object Process
  sealed trait ProcessResult
  case class Continue(nextFrame: Frame) extends ProcessResult
  case object Stop extends ProcessResult
  case object FinishedApplyingOperations

  /* Phases
  We cycle through the following update phases:

  0. `initialize` load initial state
  1. `processing` make business decisions based on the new state of the instances.
      1.1 `checking` Check if all old instances are terminal and all new instances are running, ready and healthy.
      1.2 `killing` Kill the next old instance, ie set the goal and update our internal state. We are ahead of what has been persisted to ZooKeeper.
      1.3 `launching` Scheduler one readiness check for a healthy new instance and launch a new instance if required.
  2. `updating` handle instance updates and apply them the current frame.
  */

  def updating: Receive = {
    case InstanceChanged(id, _, _, _, inst) =>
      logPrefixedInfo("updating")(s"Received update for ${readableInstanceString(inst)}")
      // Update all instances.
      currentFrame = currentFrame.copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))

      context.become(processing)
      self ! Process

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logPrefixedInfo("updating")(s"Received health update for $id: $healthy")
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      currentFrame = currentFrame
        .copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))
        .updateHealth(id, healthy.getOrElse(false))

      context.become(processing)
      self ! Process

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case result: ReadinessCheckResult =>
      logPrefixedInfo("updating")(s"Received readiness check update for ${result.taskId.instanceId} with ready: ${result.ready}")
      deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
      //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
      if (result.ready) {
        logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
        currentFrame = currentFrame.updateReadiness(result.taskId.instanceId, true)
        unsubscripeReadinessCheck(result)
      }

      context.become(processing)
      self ! Process

    // TODO(karsten): Should we re-initiate the health check?
    case ReadinessCheckStreamDone(subscriptionName, maybeFailure) =>
      maybeFailure.foreach { ex =>
        // We should not ever get here
        logger.error(s"Received an unexpected failure for readiness stream $subscriptionName", ex)
      }
      logPrefixedInfo("updating")(s"Readiness check stream $subscriptionName is done")
      subscriptions -= subscriptionName

      context.become(processing)
      self ! Process
  }

  def processing: Receive = {
    case Process =>
      logPrefixedInfo("processing")(s"Current frame: $currentFrame")
      process(completedPhases, currentFrame) match {
        case Continue(nextFrame) =>
          logPrefixedInfo("processing")("Continue handling updates")
          currentFrame = nextFrame.withoutOperations()

          logPrefixedInfo("processing")(s"Next frame: $nextFrame")

          // Replicate state in instance tracker by replaying operations.
          Future.sequence(nextFrame.operations.map { op => instanceTracker.process(op) })
            .map(_ => FinishedApplyingOperations)
            .pipeTo(self)

        case Stop =>
          logPrefixedInfo("processing")("We are done. Stopping.")
          context.stop(self)
      }
      completedPhases += 1

    case FinishedApplyingOperations =>
      logPrefixedInfo("processing")("Finished replicating state to instance tracker.")

      // TODO(karsten): LaunchQueue should start task launchers automatically
      launchQueue.add(runSpec, 0)

      // We went through all phases so lets unleash all pending instance changed updates.
      context.become(updating)
      unstashAll()

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  def process(completedPhases: Int, frame: Frame): ProcessResult = {
    if (check(frame)) {
      Stop
    } else {
      val frameAfterKilling = if (completedPhases == 0) {
        killImmediately(ignitionStrategy.nrToKillImmediately, frame)
      } else {
        killNext(frame)
      }
      val nextFrame = launching(frameAfterKilling)
      Continue(nextFrame)
    }
  }

  // Check if we are done.
  def check(frame: Frame): Boolean = {
    val instances = frame.instances
    val readableInstances = instances.values.map(readableInstanceString).mkString(",")
    logPrefixedInfo("checking")(s"Checking if we are done with new version ${runSpec.version} for $readableInstances")
    // Are all old instances terminal?
    val oldTerminal = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).forall { instance =>
      considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
    }

    // Are all new instances running, ready and healthy?
    val newActive = instances.valuesIterator.count { instance =>
      val healthy = if (hasHealthChecks) frame.instancesHealth.getOrElse(instance.instanceId, false) else true
      val ready = if (hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
      instance.runSpecVersion == runSpec.version && instance.state.condition == Condition.Running && instance.state.goal == Goal.Running && healthy && ready
    }

    val newReady = instances.valuesIterator.count { instance =>
      if (hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
    }

    val newStaged = instances.valuesIterator.count { instance =>
      instance.runSpecVersion == runSpec.version && instance.isScheduled && instance.state.goal == Goal.Running
    }

    if (oldTerminal && newActive == runSpec.instances) {
      logPrefixedInfo("checking")(s"All new instances for $pathId are ready and all old instances have been killed")
      promise.trySuccess(())
      true
    } else {
      logPrefixedInfo("checking")(s"Not done yet: old: $oldTerminal, new active: $newActive, new scheduled: $newStaged, new ready: $newReady")
      false
    }
  }

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

  // Kill next old instance
  def killNext(frame: Frame): Frame = {
    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val shouldKill = if (hasHealthChecks) frame.instancesHealth.valuesIterator.count(_ == true) >= minHealthy else true

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

  // Launch next new instance
  def launching(frame: Frame): Frame = {

    // Schedule readiness check for new healthy instance that has no scheduled check yet.
    val frameWithReadiness: Frame = if (hasReadinessChecks) {
      frame.instances.valuesIterator.find { instance =>
        val noReadinessCheckScheduled = !frame.instancesReady.contains(instance.instanceId)
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && noReadinessCheckScheduled
      } match {
        case Some(instance) =>
          logPrefixedInfo("launching")(s"Scheduling readiness check for ${instance.instanceId}.")
          initiateReadinessCheck(instance)

          // Mark new instance as not ready
          frame.updateReadiness(instance.instanceId, false)
        case None => frame
      }
    } else {
      logPrefixedInfo("launching")("No need to schedule readiness check.")
      frame
    }

    logPrefixedInfo("launching")("Launching next instance")
    val instances = frameWithReadiness.instances
    val oldTerminalInstances = instances.valuesIterator.count { instance =>
      instance.runSpecVersion < runSpec.version && considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
    }
    val oldInstances = instances.valuesIterator.count(_.runSpecVersion < runSpec.version) - oldTerminalInstances

    val newInstancesStarted = instances.valuesIterator.count { instance =>
      instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
    }
    logPrefixedInfo("launching")(s"with $oldTerminalInstances old terminal, $oldInstances old active and $newInstancesStarted new started instances.")
    launchInstances(oldInstances, newInstancesStarted, frameWithReadiness)
  }

  def launchInstances(oldInstances: Int, newInstancesStarted: Int, frame: Frame): Frame = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstances - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)

    if (instancesToStartNow > 0) {
      logPrefixedInfo("launching")(s"Queuing $instancesToStartNow new instances")
      frame.add(runSpec, instancesToStartNow)
    } else {
      logPrefixedInfo("launching")("Not queuing new instances")
      frame
    }
  }

  protected val hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }

  def logPrefixedInfo(phase: String)(msg: String): Unit = logger.info(s"Deployment ${status.plan.id} Phase $phase: $msg")

  // TODO(karsten): Remove ReadinesscheckBehaviour duplication.
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Cancellable]

  protected val hasReadinessChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.readinessChecks.nonEmpty
      case pod: PodDefinition => false // TODO(PODS) support readiness post-MVP
    }
  }

  def reconcileAlreadyStartedInstances(): Unit = {
    if (hasReadinessChecks) {
      val instancesAlreadyStarted = currentFrame.instances.valuesIterator.filter { instance =>
        instance.runSpecVersion == runSpec.version && instance.isActive
      }.toVector
      logger.info(s"Reconciling instances during ${runSpec.id} deployment: found ${instancesAlreadyStarted.size} already started instances.")
      instancesAlreadyStarted.foreach(reconcileHealthAndReadinessCheck)
    }
  }

  /**
    * Call this method for instances that are already started.
    * This should be necessary only after fail over to reconcile the state from a previous run.
    * It will make sure to wait for health checks and readiness checks to become green.
    * @param instance the instance that has been started.
    */
  def reconcileHealthAndReadinessCheck(instance: Instance): Unit = {
    currentFrame = currentFrame.updateReadiness(instance.instanceId, false)
    initiateReadinessCheck(instance)
  }

  def unsubscripeReadinessCheck(result: ReadinessCheckResult): Unit = {
    val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
    subscriptions.get(subscriptionName).foreach(_.cancel())
  }

  def initiateReadinessCheck(instance: Instance): Unit = {
    instance.tasksMap.foreach {
      case (_, task) => initiateReadinessCheckForTask(task)
    }
  }

  implicit private val materializer = ActorMaterializer()
  private def initiateReadinessCheckForTask(task: Task): Unit = {

    logger.info(s"Schedule readiness check for task: ${task.taskId}")
    ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task).foreach { spec =>
      val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
      val (subscription, streamDone) = readinessCheckExecutor.execute(spec)
        .toMat(Sink.foreach { result: ReadinessCheckResult => self ! result })(Keep.both)
        .run
      streamDone.onComplete { doneResult =>
        self ! ReadinessCheckStreamDone(subscriptionName, doneResult.failed.toOption)
      }(context.dispatcher)
      subscriptions = subscriptions + (subscriptionName -> subscription)
    }
  }

  def readableInstanceString(instance: Instance): String =
    s"Instance(id=${instance.instanceId}, version=${instance.runSpecVersion}, goal=${instance.state.goal}, condition=${instance.state.condition})"
}

object TaskReplaceActor extends StrictLogging {

  object CheckFinished

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

  case class Frame(
      instances: Map[Instance.Id, Instance],
      instancesHealth: Map[Instance.Id, Boolean],
      instancesReady: Map[Instance.Id, Boolean],
      operations: Vector[InstanceUpdateOperation] = Vector.empty) {

    def updateHealth(instanceId: Instance.Id, health: Boolean): Frame = copy(instancesHealth = instancesHealth.updated(instanceId, health))

    def updateReadiness(instanceId: Instance.Id, ready: Boolean): Frame = copy(instancesReady = instancesReady.updated(instanceId, ready))

    def setGoal(instanceId: Instance.Id, goal: Goal): Frame = {
      val op = InstanceUpdateOperation.ChangeGoal(instanceId, goal)
      val updatedState = instances(instanceId).state.copy(goal = goal)
      val updatedInstance = instances(instanceId).copy(state = updatedState)
      copy(operations = operations :+ op, instances = instances.updated(instanceId, updatedInstance))
    }

    def add(runSpec: RunSpec, count: Int): Frame = {
      val existingReservedStoppedInstances = instances.valuesIterator
        .filter(i => i.hasReservation && i.state.condition.isTerminal && i.state.goal == Goal.Stopped) // resident to relaunch
        .take(count)
      val rescheduleOperations = existingReservedStoppedInstances.map { instance => RescheduleReserved(instance.instanceId, runSpec) }

      logger.info(s"Rescheduled existing instances for ${runSpec.id}:${runSpec.version}")

      // Schedule additional resident instances or all ephemeral instances
      val instancesToSchedule = existingReservedStoppedInstances.length.until(count).map { _ => Instance.scheduled(runSpec, Instance.Id.forRunSpec(runSpec.id)) }
      val scheduleOperations = instancesToSchedule.map { instance => InstanceUpdateOperation.Schedule(instance) }

      logger.info(s"Scheduling ${instancesToSchedule.length} new instances (first five: ${instancesToSchedule.take(5)} ) " +
        s"and rescheduling (${existingReservedStoppedInstances.length}) reserved instances for ${runSpec.id}:${runSpec.version}")

      // Updated internal state with scheduled and rescheduled instances
      val updatedInstances = existingReservedStoppedInstances.foldLeft(instances) {
        case (acc, instance) =>
          val updatedState = instance.state.copy(goal = Goal.Running)
          acc.updated(instance.instanceId, instance.copy(state = updatedState, runSpec = runSpec))
      } ++ instancesToSchedule.map(i => i.instanceId -> i)

      // Update operations
      val updatedOperations = operations ++ rescheduleOperations ++ scheduleOperations
      copy(operations = updatedOperations, instances = updatedInstances)
    }

    def withoutOperations(): Frame = copy(operations = Vector.empty)
  }

  object Frame {
    def apply(instances: Map[Instance.Id, Instance]): Frame = {
      val instancesHealth: Map[Instance.Id, Boolean] = instances.collect {
        case (id, instance) => id -> instance.state.healthy.getOrElse(false)
      }(collection.breakOut)
      val instancesReady: Map[Instance.Id, Boolean] = Map.empty
      new Frame(instances, instancesHealth, instancesReady)
    }
  }
}

