package mesosphere.marathon
package core.deployment.impl

import akka.Done
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
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}

import scala.async.Async.{async, await}
import scala.collection.mutable
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
  var instances: mutable.Map[Instance.Id, Instance] = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut)
  val instancesHealth: mutable.Map[Instance.Id, Boolean] = instances.collect {
    case (id, instance) => id -> instance.state.healthy.getOrElse(false)
  }
  val instancesReady: mutable.Map[Instance.Id, Boolean] = mutable.Map.empty
  var complectedPhases: Int = 0

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // reconcile the state from a possible previous run
    reconcileAlreadyStartedInstances()

    // Kill instances with Goal.Decommissioned. This is a quick fix until #6745 lands.
    val doomed: Seq[Instance] = instances.valuesIterator.filter { instance =>
      (instance.state.goal == Goal.Decommissioned || instance.state.goal == Goal.Stopped) && !considerTerminal(instance.state.condition)
    }.to[Seq]
    killService.killInstancesAndForget(doomed, KillReason.Upgrading)

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
  case object Continue extends ProcessResult
  case object Stop extends ProcessResult

  /* Phases
  We cycle through the following update phases:

  0. `initialize` load initial state
  1. `processing` make business decisions based on the new state of the instances.
      1.1 `checking` Check if all old instances are terminal and all new instances are running, ready and healthy.
      1.2 `killing` Kill the next old instance, ie set the goal and update our internal state. We are ahead of what has been persisted to ZooKeeper.
      1.3 `launching` Scheduler one readiness check for a healthy new instance and launch a new instance if required.
  2. `updating` handle instance updates and apply them to our internal state.
  */

  def updating: Receive = {
    case InstanceChanged(id, _, _, _, inst) =>
      logPrefixedInfo("updating")(s"Received update for ${readableInstanceString(inst)}")
      // Update all instances.
      instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut)
      /*
      instanceTracker.instancesBySpecSync.instance(id) match {
        case Some(instance) =>
          instances += id -> instance
        case None =>
          logPrefixedInfo("updating")(s"Removing $id")
          instances.remove(id)
          instancesHealth.remove(id)
          instancesReady.remove(id)
      }
      */

      context.become(processing)
      self ! Process

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logPrefixedInfo("updating")(s"Received health update for $id: $healthy")
      instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut)
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      instancesHealth += id -> healthy.getOrElse(false)

      context.become(processing)
      self ! Process

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case result: ReadinessCheckResult =>
      logPrefixedInfo("updating")(s"Received readiness check update for ${result.taskId.instanceId} with ready: ${result.ready}")
      deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
      //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
      if (result.ready) {
        logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
        instancesReady += result.taskId.instanceId -> true
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
      process(complectedPhases).pipeTo(self)
      complectedPhases += 1

    case Continue =>
      logPrefixedInfo("processing")("Continue handling updates")
      context.become(updating)

      // We went through all phases so lets unleash all pending instance changed updates.
      unstashAll()

    case Stop =>
      logPrefixedInfo("processing")("We are done. Stopping.")
      context.stop(self)

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  def process(completedPhases: Int): Future[ProcessResult] = async {
    if (check()) {
      Stop
    } else {
      if (completedPhases == 0) {
        val killed = await(killImmediately(ignitionStrategy.nrToKillImmediately))
      } else {
        val killed = await(killing())
      }
      val launched = await(launching())
      Continue
    }
  }

  // Check if we are done.
  def check(): Boolean = {
    val readableInstances = instances.values.map(readableInstanceString).mkString(",")
    logPrefixedInfo("checking")(s"Checking if we are done with new version ${runSpec.version} for $readableInstances")
    // Are all old instances terminal?
    val oldTerminal = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).forall { instance =>
      considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
    }

    // Are all new instances running, ready and healthy?
    val newActive = instances.valuesIterator.count { instance =>
      val healthy = if (hasHealthChecks) instancesHealth.getOrElse(instance.instanceId, false) else true
      val ready = if (hasReadinessChecks) instancesReady.getOrElse(instance.instanceId, false) else true
      instance.runSpecVersion == runSpec.version && instance.state.condition == Condition.Running && instance.state.goal == Goal.Running && healthy && ready
    }

    val newReady = instances.valuesIterator.count { instance =>
      if (hasReadinessChecks) instancesReady.getOrElse(instance.instanceId, false) else true
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

  def killImmediately(oldInstances: Int): Future[Done] = async {
    logPrefixedInfo("killing")(s"Killing $oldInstances immediately.")
    val killedIds = await(instances.valuesIterator
      .filter { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      }
      .take(oldInstances)
      .foldLeft(Future.successful(Seq.empty[Instance.Id])) { (acc, nextDoomed) =>
        async {
          val current = await(acc)
          await(killNextOldInstance(nextDoomed))
          current :+ nextDoomed.instanceId
        }
      })
    if (killedIds.nonEmpty) logPrefixedInfo("killing")(s"Marking $killedIds as stopped.")
    else logPrefixedInfo("killing")("Nothing marked as stopped.")

    // TODO(karsten): Bad. We are modifying actor state from a future.
    killedIds.foreach { instanceId =>
      val killedInstance = instances(instanceId)
      val updatedState = killedInstance.state.copy(goal = Goal.Stopped)
      instances += instanceId -> killedInstance.copy(state = updatedState)
    }

    Done
  }

  // Kill next old instance
  def killing(): Future[Done] = async {
    //Kill next
    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val shouldKill = if (hasHealthChecks) instancesHealth.valuesIterator.count(_ == true) >= minHealthy else true

    if (shouldKill) {
      logPrefixedInfo("killing")("Picking next old instance.")
      // Pick first active old instance that has goal running
      instances.valuesIterator.find { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      } match {
        case Some(doomed) =>
          await(killNextOldInstance(doomed))
          val instanceId = doomed.instanceId
          logPrefixedInfo("killing")(s"Marking $instanceId as stopped.")
          val killedInstance = instances(instanceId)
          val updatedState = killedInstance.state.copy(goal = Goal.Stopped)

          // TODO(karsten): Bad. We are modifying actor state from a future.
          instances += instanceId -> killedInstance.copy(state = updatedState)
        case None =>
          logPrefixedInfo("killing")("No next instance to kill.")
      }
    } else {
      val currentHealthyInstances = instancesHealth.valuesIterator.count(_ == true)
      logPrefixedInfo("killing")(s"Not killing next instance because $currentHealthyInstances healthy but minimum $minHealthy required.")
    }

    Done
  }

  // Launch next new instance
  def launching(): Future[Done] = async {
    // Schedule readiness check for new healthy instance that has no scheduled check yet.
    if (hasReadinessChecks) {
      instances.valuesIterator.find { instance =>
        val noReadinessCheckScheduled = !instancesReady.contains(instance.instanceId)
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && noReadinessCheckScheduled
      } foreach { instance =>
        logPrefixedInfo("launching")(s"Scheduling readiness check for ${instance.instanceId}.")
        initiateReadinessCheck(instance)

        // Mark new instance as not ready
        // TODO(karsten): Bad. We are modifying actor state from a future.
        instancesReady += instance.instanceId -> false
      }
    } else {
      logPrefixedInfo("launching")("No need to schedule readiness check.")
    }

    logPrefixedInfo("launching")("Launching next instance")
    val oldTerminalInstances = instances.valuesIterator.count { instance =>
      instance.runSpecVersion < runSpec.version && considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
    }

    val oldInstances = instances.valuesIterator.count(_.runSpecVersion < runSpec.version) - oldTerminalInstances

    val newInstancesStarted = instances.valuesIterator.count { instance =>
      instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
    }
    logPrefixedInfo("launching")(s"with $oldTerminalInstances old terminal, $oldInstances old active and $newInstancesStarted new started instances.")
    val scheduledInstances = await(launchInstances(oldInstances, newInstancesStarted))

    logPrefixedInfo("launching")(s"Marking ${scheduledInstances.map(_.instanceId)} as scheduled.")
    // We take note of all scheduled instances before accepting new updates so that we do not overscale.
    scheduledInstances.foreach { instance =>
      // The launch queue actor does not change instances so we have to ensure that the goal is running.
      // These instance will be overridden by new updates but for now we just need to know that we scheduled them.
      val updatedState = instance.state.copy(goal = Goal.Running)
      instances += instance.instanceId -> instance.copy(state = updatedState, runSpec = runSpec)
    }

    Done
  }

  def launchInstances(oldInstances: Int, newInstancesStarted: Int): Future[Seq[Instance]] = async {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstances - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)

    if (instancesToStartNow > 0) {
      logPrefixedInfo("launching")(s"Queuing $instancesToStartNow new instances")
      await(launchQueue.addWithReply(runSpec, instancesToStartNow))
    } else {
      logPrefixedInfo("launching")("Not queuing new instances")
      Seq.empty
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
          logPrefixedInfo("killing")(s"Killing old ${nextOldInstance.instanceId}")

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
      val instancesAlreadyStarted = instances.valuesIterator.filter { instance =>
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
    instancesReady += instance.instanceId -> false
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

