package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.async.Async.{async, await}
import scala.collection.{SortedSet, mutable}
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
    promise: Promise[Unit]
) extends Actor
    with Stash
    with ReadinessBehavior
    with StrictLogging {
  import TaskReplaceActor._

  def deploymentId = status.plan.id

  var state: TaskReplaceActor.State = TaskReplaceActor.State(runSpec, Seq.empty)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()

    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    instanceTracker
      .specInstances(runSpec.id, readAfterWrite = true)
      .map(instances => TaskReplaceActor.State(runSpec, instances))
      .pipeTo(self)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case loadedState: TaskReplaceActor.State =>
      state = loadedState

      // reconcile the state from a possible previous run
      reconcileAlreadyStartedInstances()

      // kill old instances to free some capacity
      for (_ <- 0 until state.ignitionStrategy.nrToKillImmediately) killNextOldInstance()

      // start new instances, if possible
      launchInstances().pipeTo(self)

      // reset the launch queue delay
      logger.info("Resetting the backoff delay before restarting the runSpec")
      launchQueue.resetDelay(runSpec)

      // it might be possible, that we come here, but nothing is left to do
      checkFinished()
    case Done =>
      unstashAll()
      context.become(initialized)

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      logger.debug(s"Stashing $stashMe")
      stash()
  }

  private def initialized: Receive = readinessBehavior orElse replaceBehavior

  /**
    * This actor is a bad example on how future orchestrator might handle the instances. The logic below handles instance
    * changed across three dimensions:
    *
    * a) old vs. new - instances with old RunSpec version are gradually replaced with the new one
    * b) goals - it's not enough to check instance condition e.g. if the new instance task FAILED but the goal is
    *            Goal.Running then it will be automatically rescheduled by the TaskLauncherActor
    * c) condition - we additionally check whether or not the instance is considered terminal/active
    *
    * What makes it so hard to work with, is the fact, that it basically counts old and new instances and the additional
    * dimensions are expressed through filters on the [[InstanceChanged]] events. It can be a more robust state-machine
    * which ideally has a set of new and old instances which then decommissions old ones and schedules new ones, never
    * incrementing/decrementing counters and never over/under scales.
    *
    */
  def replaceBehavior: Receive = {

    // === An InstanceChanged event for the *new* instance ===
    case InstanceChanged(id, _, `pathId`, condition, instance) if !isOldInstance(instance) =>
      val goal = instance.state.goal
      val agentId = instance.agentInfo.fold(Option.empty[String])(_.agentId)

      // 1) Did the new instance task fail?
      if (considerTerminal(condition) && goal == Goal.Running) {
        logger.warn(
          s"Deployment $deploymentId: New $id is terminal ($condition) on agent $agentId during app $pathId restart: " +
            s"$condition reservation: ${instance.reservation}. Waiting for the task to restart..."
        )
        instanceTerminated(id)
      } // 2) Did someone tamper with new instance's goal? Don't do that - there should be only one "orchestrator" per service per time!
      else if (considerTerminal(condition) && goal.isTerminal()) {
        logger.error(
          s"Deployment $deploymentId: New $id is terminal ($condition) on agent $agentId during app $pathId restart " +
            s"(reservation: ${instance.reservation}) and the goal ($goal) is *NOT* Running! This means that someone is interfering with current deployment!"
        )
        state.instancesStarted -= 1
      } else {
        logger.info(
          s"Deployment $deploymentId: Unhandled InstanceChanged event for new instanceId=$id, condition=$condition " +
            s"(considered terminal=${considerTerminal(condition)}) and current goal=${instance.state.goal}"
        )
      }

    // === An InstanceChanged event for the *old* instance ===
    case InstanceChanged(id, _, `pathId`, condition, instance) if isOldInstance(instance) =>
      val goal = instance.state.goal

      // 1) An old instance terminated out of band and was not yet chosen to be decommissioned or stopped.
      // We stop/decommission the instance and let it be rescheduled with new instance RunSpec
      if (considerTerminal(condition) && goal == Goal.Running) {
        logger.info(
          s"Deployment $deploymentId: Old $id became $condition during an upgrade but still has goal Running. " +
            "We will decommission that instance and launch new one with the new RunSpec."
        )
        state.oldActiveInstanceIds -= id
        instanceTerminated(id)
        val goal = if (runSpec.isResident) Goal.Stopped else Goal.Decommissioned
        instanceTracker
          .setGoal(instance.instanceId, goal, GoalChangeReason.Upgrading)
          .pipeTo(self)
      } // 2) An old and decommissioned instance was successfully killed (or was never launched in the first place if condition == Scheduled)
      else if ((considerTerminal(condition) || condition == Condition.Scheduled) && instance.state.goal.isTerminal()) {
        logger.info(s"Deployment $deploymentId: Old $id became $condition. Launching more instances.")
        state.oldActiveInstanceIds -= id
        instanceTerminated(id)
        launchInstances()
          .map(_ => CheckFinished)
          .pipeTo(self)
      } else {
        logger.info(
          s"Deployment $deploymentId: Unhandled InstanceChanged event for an old instanceId=$id, condition=$condition " +
            s"(considered terminal=${considerTerminal(condition)}) and goal=${instance.state.goal}"
        )
      }

    case Status.Failure(e) =>
      // This is the result of failed launchQueue.addAsync(...) call. Log the message and
      // restart this actor. Next reincarnation should try to start from the beginning.
      logger.warn(s"Deployment $deploymentId: Failed to launch instances: ", e)
      throw e

    case Done => // This is the result of successful launchQueue.addAsync(...) call. Nothing to do here

    case CheckFinished => checkFinished()

  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    if (healthyInstances(instanceId) && readyInstances(instanceId)) killNextOldInstance(Some(instanceId))
    checkFinished()
  }

  def reconcileAlreadyStartedInstances(): Unit = {
    logger.info(
      s"Deployment $deploymentId: Reconciling instances during ${runSpec.id} deployment: found ${state.instancesAlreadyStarted.size} already started instances " +
        s"and ${state.oldActiveInstanceIds.size} old instances: ${if (state.currentInstances.size > 0)
          state.currentInstances.map { i => i.instanceId -> i.state.condition }
        else "[]"}"
    )
    state.instancesAlreadyStarted.foreach(reconcileHealthAndReadinessCheck)
  }

  // Careful not to make this method completely asynchronous - it changes local actor's state `instancesStarted`.
  // Only launching new instances needs to be asynchronous.
  def launchInstances(): Future[Done] = {
    val leftCapacity = math.max(0, state.ignitionStrategy.maxCapacity - state.oldActiveInstanceIds.size - state.instancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - state.instancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      logger.info(
        s"Deployment $deploymentId: Restarting app $pathId: queuing $instancesToStartNow new instances since leftCapacity = $leftCapacity, " +
          s"instancesStarted = ${state.instancesStarted}, instancesNotStartedYet = $instancesNotStartedYet and instancesToStartNow = $instancesToStartNow"
      )
      state.instancesStarted += instancesToStartNow
      launchQueue.add(runSpec, instancesToStartNow)
    } else {
      logger.info(
        s"Deployment $deploymentId: Restarting app $pathId. No need to start new instances right now with leftCapacity = $leftCapacity, " +
          s"instancesStarted = ${state.instancesStarted}, instancesNotStartedYet = $instancesNotStartedYet and instancesToStartNow = $instancesToStartNow"
      )
      Future.successful(Done)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def killNextOldInstance(maybeNewInstanceId: Option[Instance.Id] = None): Unit = {
    if (state.toKill.nonEmpty) {
      val dequeued = state.toKill.dequeue()
      async {
        await(instanceTracker.get(dequeued)) match {
          case None =>
            logger.warn(
              s"Deployment $deploymentId: Was about to kill instance $dequeued but it did not exist in the instance tracker anymore."
            )
          case Some(nextOldInstance) =>
            maybeNewInstanceId match {
              case Some(newInstanceId: Instance.Id) =>
                logger.info(s"Deployment $deploymentId: Killing old ${nextOldInstance.instanceId} because $newInstanceId became reachable")
              case _ =>
                logger.info(s"Deployment $deploymentId: Killing old ${nextOldInstance.instanceId}")
            }

            val goal = if (runSpec.isResident) Goal.Stopped else Goal.Decommissioned
            await(instanceTracker.setGoal(nextOldInstance.instanceId, goal, GoalChangeReason.Upgrading))
        }
      }
    }
  }

  def checkFinished(): Unit = {
    if (targetCountReached(runSpec.instances) && state.oldActiveInstanceIds.isEmpty) {
      logger.info(s"Deployment $deploymentId: All new instances for $pathId are ready and all old instances have been killed")
      promise.trySuccess(())
      context.stop(self)
    } else {
      logger.info(
        s"Deployment $deploymentId: For run spec: [${runSpec.id}] there are [${healthyInstances.size}] healthy and " +
          s"[${readyInstances.size}] ready new instances and " +
          s"[${state.oldActiveInstanceIds.size}] old instances (${state.oldActiveInstanceIds.take(3).map(_.idString).mkString("[", ",", "]")}). " +
          s"Target count is ${runSpec.instances}."
      )
    }
  }

  /**
    * @return whether [[Instance]] has the new run spec version or an old one.
    */
  def isOldInstance(instance: Instance): Boolean = {
    require(instance.runSpecId == runSpec.id) // sanity check
    instance.runSpecVersion != runSpec.version
  }
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
      promise: Promise[Unit]
  ): Props =
    Props(
      new TaskReplaceActor(deploymentManagerActor, status, launchQueue, instanceTracker, eventBus, readinessCheckExecutor, app, promise)
    )

  /**
    * Presents the internal state of the replace actor.
    *
    * @param runSpec The current [[RunSpec]].
    * @param specInstances The current instances of the run spec.
    */
  case class State(runSpec: RunSpec, specInstances: Seq[Instance]) {

    // All existing instances of this app independent of the goal.
    //
    // Killed resident tasks are not expunged from the instances list. Ignore
    // them. LaunchQueue takes care of launching instances against reservations
    // first
    val currentInstances = specInstances

    // In case previous master was abdicated while the deployment was still running we might have
    // already started some new tasks.
    // All already started and active tasks are filtered while the rest is considered
    val (instancesAlreadyStarted, oldInstances) = {
      currentInstances.partition(_.runSpecVersion == runSpec.version)
    }

    // The ignition strategy for this run specification
    val ignitionStrategy = computeRestartStrategy(runSpec, currentInstances)

    // compute all variables maintained in this actor =========================================================

    // Only old instances that still have the Goal.Running
    val oldActiveInstances = oldInstances.filter(_.state.goal == Goal.Running)

    // All instances to kill as set for quick lookup
    var oldActiveInstanceIds: SortedSet[Id] = oldActiveInstances.map(_.instanceId).to(SortedSet)

    // instance to kill sorted by decreasing order of priority
    // we always prefer to kill unhealthy tasks first
    val toKillOrdered = oldActiveInstances.sortWith((i1, i2) => {
      (i1.consideredHealthy, i2.consideredHealthy) match {
        case (_, false) => false
        case _ => true
      }
    })

    // All instances to kill queued up
    val toKill: mutable.Queue[Instance.Id] = toKillOrdered.map(_.instanceId).to(mutable.Queue)

    // The number of started or scheduled instances. Defaults to the number of already started instances.
    var instancesStarted: Int = instancesAlreadyStarted.size
  }

  /** Encapsulates the logic how to get a Restart going */
  private[impl] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  private[impl] def computeRestartStrategy(runSpec: RunSpec, currentInstances: Seq[Instance]): RestartStrategy = {
    // in addition to a spec which passed validation, we require:
    require(runSpec.instances > 0, s"instances must be > 0 but is ${runSpec.instances}")
    require(currentInstances.size >= 0, s"current instances count must be >=0 but is ${currentInstances.size}")

    // Old and new instances that have the Goal.Running & are considered healthy
    val consideredHealthyInstancesCount = currentInstances.filter(i => i.state.goal == Goal.Running && i.consideredHealthy).size
    require(consideredHealthyInstancesCount >= 0, s"running instances count must be >=0 but is $consideredHealthyInstancesCount")

    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, consideredHealthyInstancesCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= consideredHealthyInstancesCount) {
      if (runSpec.isResident) {
        // Kill enough instances so that we end up with one instance below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = consideredHealthyInstancesCount - minHealthy + 1
        logger.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        logger.info("maxCapacity == minHealthy: Allow temporary over-capacity of one instance to allow restarting")
        maxCapacity += 1
      }
    }

    // following condition addresses cases where we have extra-instances due to previous deployment adding extra-instances
    // and deployment is force-updated
    if (runSpec.instances < currentInstances.size) {
      nrToKillImmediately = math.max(currentInstances.size - runSpec.instances, nrToKillImmediately)
      logger.info(s"runSpec.instances < currentInstances: Allowing killing all $nrToKillImmediately extra-instances")
    }

    logger.info(
      s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
        s"$minHealthy instances running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
        s"$consideredHealthyInstancesCount running instances immediately. (RunSpec version ${runSpec.version})"
    )

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewInstances: Boolean = minHealthy < maxCapacity || consideredHealthyInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewInstances, "must be able to start new instances")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}
