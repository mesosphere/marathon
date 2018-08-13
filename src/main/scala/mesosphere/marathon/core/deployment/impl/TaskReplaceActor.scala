package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.KillReason
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.stream.EnrichedSource

import scala.async.Async.{async, await}
import scala.collection.{SortedSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val scheduler: scheduling.Scheduler,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with ReadinessBehavior with StrictLogging {
  import TaskReplaceActor._

  // compute all values ====================================================================================

  // All running instances of this app
  //
  // Killed resident tasks are not expunged from the instances list. Ignore
  // them. LaunchQueue takes care of launching instances against reservations
  // first
  val currentInstances = {
    // TODO(karsten): Use InstanceTrackerConfig.internalTaskTrackerRequestTimeout or rather be async.
    Await.result(scheduler.getInstances(runSpec.id), 1000.millis).filter(_.state.goal == Goal.Running)
  }

  // In case previous master was abdicated while the deployment was still running we might have
  // already started some new tasks.
  // All already started and active tasks are filtered while the rest is considered
  private[this] val (instancesAlreadyStarted, instancesToRemove) = {
    currentInstances.partition(_.runSpecVersion == runSpec.version)
  }

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, currentInstances.size)

  // compute all variables maintained in this actor =========================================================

  // All instances to kill as set for quick lookup
  private[this] var oldInstanceIds: SortedSet[Id] = instancesToRemove.map(_.instanceId).to[SortedSet]

  // All instances to kill queued up
  private[this] val toKill: mutable.Queue[Instance.Id] = instancesToRemove.map(_.instanceId).to[mutable.Queue]

  // The number of started instances. Defaults to the number of already started instances.
  var instancesStarted: Int = instancesAlreadyStarted.size

  // We instantiate the materializer here so that all materialized streams end up as children of this actor
  implicit val materializer = ActorMaterializer()

  val bufferSize = Int.MaxValue
  val overflowStrategy = OverflowStrategy.fail
  val frames = EnrichedSource.eventBusSource(classOf[InstanceChanged], eventBus, bufferSize, overflowStrategy).mapAsync(1) { event =>
    async {
      val instances = await(scheduler.getInstances(runSpec.id))
      Frame(instances, event)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // reconcile the state from a possible previous run
    reconcileAlreadyStartedInstances()

    frames.runWith(Sink.actorRef(self, NoMoreFrames))

    async {
      // Update run spec in task launcher actor.
      // Currently the [[TaskLauncherActor]] always starts instances with the latest run spec. Let's say there are 2
      // running instances with v1 and 3 scheduled for v1. If the users forces an update to v2 the current logic will
      // kill the 2 running instances and only tell the [[TaskLauncherActor]] to start the 3 scheduled v1 instances with
      // the v2 run spec. We then schedule 2 more v2 instances. In the future we probably want to bind instances to a
      // certain run spec. Until then we have to update the run spec in a [[TaskLauncherActor]]
      await(scheduler.sync(runSpec))

      // kill old instances to free some capacity
      for (_ <- 0 until ignitionStrategy.nrToKillImmediately) killNextOldInstance()

      // start new instances, if possible
      val instances = await(scheduler.getInstances(runSpec.id))
      val launched = await(launchInstances(instances))

      // reset the launch queue delay
      logger.info("Resetting the backoff delay before restarting the runSpec")
      scheduler.resetDelay(runSpec)

      // it might be possible, that we come here, but nothing is left to do
      checkFinished()

      Done
    }.pipeTo(self)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case Done =>
      context.become(initialized)
      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private def initialized: Receive = readinessBehavior orElse replaceBehavior

  def replaceBehavior: Receive = {
    // New instance failed to start, restart it
    case Frame(instances, InstanceChanged(id, `version`, `pathId`, condition, Instance(_, Some(agentInfo), _, _, _, _, reservation))) if !oldInstanceIds(id) && considerTerminal(condition) =>
      logger.warn(s"New instance $id is terminal on agent ${agentInfo.agentId} during app $pathId restart: $condition reservation: $reservation")
      instanceTerminated(id)
      instancesStarted -= 1
      launchInstances(instances).pipeTo(self)

    // Old instance successfully killed
    case Frame(instances, InstanceChanged(id, _, `pathId`, condition, _)) if oldInstanceIds(id) && considerTerminal(condition) =>
      logger.info(s"Instance $id became $condition. Launching more instances.")
      oldInstanceIds -= id
      instanceTerminated(id)
      launchInstances(instances).map(_ => CheckFinished).pipeTo(self)

    // Ignore change events, that are not handled in parent receives
    case Frame(_, event) =>
      self ! event

    case Status.Failure(e) =>
      // This is the result of failed launchQueue.addAsync(...) call. Log the message and
      // restart this actor. Next reincarnation should try to start from the beginning.
      logger.warn("Failed to launch instances: ", e)
      throw e

    case Done => // This is the result of successful launchQueue.addAsync(...) call. Nothing to do here

    case CheckFinished => checkFinished()

  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    if (healthyInstances(instanceId) && readyInstances(instanceId)) killNextOldInstance(Some(instanceId))
    checkFinished()
  }

  def reconcileAlreadyStartedInstances(): Unit = {
    logger.info(s"reconcile: found ${instancesAlreadyStarted.size} already started instances " +
      s"and ${oldInstanceIds.size} old instances: ${currentInstances.map{ i => i.instanceId -> i.state.condition }}")
    instancesAlreadyStarted.foreach(reconcileHealthAndReadinessCheck)
  }

  // Careful not to make this method completely asynchronous - it changes local actor's state `instancesStarted`.
  // Only launching new instances needs to be asynchronous.
  def launchInstances(instances: Seq[Instance]): Future[Done] = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstanceIds.size - instancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - instancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      logger.info(s"Reconciling instances during app $pathId restart: queuing $instancesToStartNow new instances")
      instancesStarted += instancesToStartNow
      async {
        // Reschedule stopped resident instances first.
        val existingReservedStoppedInstances = instances
          .filter(i => i.isReserved && i.state.goal == Goal.Stopped) // resident to relaunch
          .take(instancesToStartNow)
        await(scheduler.reschedule(existingReservedStoppedInstances, runSpec))

        // Schedule remaining instances
        val instancesToSchedule = math.max(0, instancesToStartNow - existingReservedStoppedInstances.length)
        await(scheduler.schedule(runSpec, instancesToSchedule))
      }
    } else {
      Future.successful(Done)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def killNextOldInstance(maybeNewInstanceId: Option[Instance.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val dequeued = toKill.dequeue()
      async {
        await(scheduler.getInstance(dequeued)) match {
          case None =>
            logger.warn(s"Was about to kill instance ${dequeued} but it did not exist in the instance tracker anymore.")
          case Some(nextOldInstance) =>
            maybeNewInstanceId match {
              case Some(newInstanceId: Instance.Id) =>
                logger.info(s"Killing old ${nextOldInstance} because $newInstanceId became reachable")
              case _ =>
                logger.info(s"Killing old ${nextOldInstance}")
            }

            if (runSpec.isResident) {
              await(scheduler.stop(nextOldInstance, KillReason.Upgrading))
            } else {
              await(scheduler.decommission(nextOldInstance, KillReason.Upgrading))
            }
        }
      }
    }
  }

  def checkFinished(): Unit = {
    if (targetCountReached(runSpec.instances) && oldInstanceIds.isEmpty) {
      logger.info(s"All new instances for $pathId are ready and all old instances have been killed")
      promise.trySuccess(())
      context.stop(self)
    } else {
      logger.info(s"For run spec: [${runSpec.id}] there are [${healthyInstances.size}] healthy and " +
        s"[${readyInstances.size}] ready new instances and " +
        s"[${oldInstanceIds.size}] old instances (${oldInstanceIds.take(3)}). Target count is ${runSpec.instances}.")
    }
  }
}

object TaskReplaceActor extends StrictLogging {

  object CheckFinished

  case class Frame(instances: Seq[Instance], event: InstanceChanged)
  case object NoMoreFrames

  //scalastyle:off
  def props(
    deploymentManagerActor: ActorRef,
    status: DeploymentStatus,
    scheduler: scheduling.Scheduler,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManagerActor, status, scheduler, eventBus, readinessCheckExecutor, app, promise)
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

