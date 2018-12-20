package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.ReadinessCheckUpdate
import mesosphere.marathon.core.deployment.impl.ReadinessBehavior.{ReadinessCheckStreamDone, ReadinessCheckSubscriptionKey}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance.InstanceState
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
  val instancesReady: mutable.Map[Instance.Id, Boolean] = mutable.Map.empty

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // kill old instances to free some capacity
    logger.info("Sending immediate kill")
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
      logger.info(s"Received update for $instance")
      instances += id -> instance

      context.become(checking)
      self ! Check

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logger.info(s"Received health update for $id: $healthy")
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      instancesHealth += id -> healthy.getOrElse(false)

      context.become(checking)
      self ! Check

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case result: ReadinessCheckResult =>
      logger.info(s"Received readiness check update for task ${result.taskId} with ready: ${result.ready}")
      deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
      //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
      if (result.ready) {
        logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
        instancesReady += result.taskId.instanceId -> true
        unsubscripeReadinessCheck(result)
      }

      context.become(checking)
      self ! Check

    // TODO(karsten): Should we re-initiate the health check?
    case ReadinessCheckStreamDone(subscriptionName, maybeFailure) =>
      maybeFailure.foreach { ex =>
        // We should not ever get here
        logger.error(s"Received an unexpected failure for readiness stream $subscriptionName", ex)
      }
      logger.debug(s"Readiness check stream $subscriptionName is done")
      subscriptions -= subscriptionName

      context.become(checking)
      self ! Check
  }

  // Check if we are done.
  def checking: Receive = {
    case Check =>
      logger.info(s"Checking if we are done for $instances")
      // Are all old instances terminal?
      val oldTerminal = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).forall { instance =>
        considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
      }

      // Are all new instances running, ready and healthy?
      val newActive = instances.valuesIterator.count { instance =>
        val healthy = if (hasHealthChecks) instancesHealth.getOrElse(instance.instanceId, false) else true
        val ready = if (hasReadinessChecks) instancesReady.getOrElse(instance.instanceId, false) else true
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && healthy && ready
      }

      if (oldTerminal && newActive == runSpec.instances) {
        logger.info(s"All new instances for $pathId are ready and all old instances have been killed")
        promise.trySuccess(())
        context.stop(self)
      } else {
        logger.info(s"Not done yet: Old: $oldTerminal, New: $newActive")
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
      logger.info(s"Killing $oldInstances immediately.")
      instances.valuesIterator
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
        }.map(Killed).pipeTo(self)

    case KillNext =>
      // Pick first active old instance that has goal running
      instances.valuesIterator.find { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      } match {
        case Some(doomed) => killNextOldInstance(doomed).map(_ => Killed(Seq(doomed.instanceId))).pipeTo(self)
        case None => self ! Killed(Seq.empty)
      }

    case Killed(killIds) =>
      logger.info(s"Marking $killIds as stopped.")
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
      logger.info("Launching next instance")
      val oldActiveInstances = instances.valuesIterator.count { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running
      }
      val newInstancesStarted = instances.valuesIterator.count { instance =>
        instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
      }
      launchInstances(oldActiveInstances, newInstancesStarted).pipeTo(self)

    case scheduledInstances: Seq[Instance] =>
      logger.info(s"Mark $scheduledInstances as scheduled.")
      // We take note of all scheduled instances before accepting new updates so that we do not overscale.
      scheduledInstances.foreach { instance =>
        // The launch queue actor does not change instances so we have to ensure that the goal is running.
        // These instance will be overridden by new updates but for now we just need to know that we scheduled them.
        val updatedState = instance.state.copy(goal = Goal.Running)
        instances += instance.instanceId -> instance.copy(state = updatedState)
        if (hasReadinessChecks) initiateReadinessCheck(instance)
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

  // TODO(karsten): Remove ReadinesscheckBehaviour duplication.
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Cancellable]

  protected val hasReadinessChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.readinessChecks.nonEmpty
      case pod: PodDefinition => false // TODO(PODS) support readiness post-MVP
    }
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

    logger.debug(s"Schedule readiness check for task: ${task.taskId}")
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

