package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.pattern._
import akka.actor.{Actor, ActorRef, Props, Status}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{DeploymentStatus, InstanceChanged, InstanceHealthChanged}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.async.Async.{async, await}
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import TaskStartActor._
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal

class TaskStartActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with StrictLogging with ReadinessBehavior {

  override def preStart(): Unit = {
    if (hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    async {
      await(initializeStart())
      // We send ourselves a message to check if we're already finished since checking it in this async block
      // would lead to a race condition because it's touching actor's state.
      PostStart
    }.pipeTo(self)
  }

  override def receive: Receive = readinessBehavior orElse commonBehavior

  def commonBehavior: Receive = {
    case InstanceChanged(id, `version`, `pathId`, condition: Condition, instance) =>
      val goal = instance.state.goal
      val agentId = instance.agentInfo.fold(Option.empty[String])(_.agentId)

      // 1) Did the new instance task fail?
      if (considerTerminal(condition) && goal == Goal.Running) {
        logger.warn(s"New $id is terminal ($condition) on agent $agentId during app $pathId restart: $condition reservation: ${instance.reservation}. Waiting for the task to restart...")
        instanceTerminated(id)
      } // 2) Did someone tamper with new instance's goal? Don't do that - there should be only one "orchestrator" per service per time!
      else if (considerTerminal(condition) && goal != Goal.Running) {
        logger.error(s"New $id is terminal ($condition) on agent $agentId during app $pathId restart (reservation: ${instance.reservation}) and the goal ($goal) is *NOT* Running! This means that someone is interfering with current deployment!")
        instanceTerminated(id)
        launchQueue.add(runSpec, 1).pipeTo(self)
      } else {
        logger.info(s"Unhandled InstanceChanged event for new instanceId=$id, considered terminal=${considerTerminal(condition)} and current goal=${instance.state.goal}")
      }

    case Sync => async {
      val instances = await(instanceTracker.specInstances(runSpec.id))
      val actualSize = instances.count { i => i.isActive || i.isScheduled }
      val instancesToStartNow = Math.max(scaleTo - actualSize, 0)
      logger.debug(s"Sync start instancesToStartNow=$instancesToStartNow appId=${runSpec.id}")
      if (instancesToStartNow > 0) {
        logger.info(s"Reconciling app ${runSpec.id} scaling: queuing $instancesToStartNow new instances")
        await(launchQueue.add(runSpec, instancesToStartNow))
      }
      context.system.scheduler.scheduleOnce(syncInterval, self, Sync)
      Done // Otherwise we will pipe the result of scheduleOnce(...) call which is a Cancellable
    }.pipeTo(self)

    case Status.Failure(e) =>
      // This is the result of failed initializeStart(...) call. Log the message and
      // restart this actor. Next reincarnation should try to start from the beginning.
      logger.warn(s"Failure in the ${getClass.getSimpleName} deployment actor: ", e)
      throw e

    case PostStart =>
      checkFinished()
      context.system.scheduler.scheduleOnce(firstSyncDelay, self, Sync)

    case Done => // This is the result of successful initializeStart(...) call. Nothing to do here
  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    logger.debug(s"New instance $instanceId changed during app ${runSpec.id} scaling, " +
      s"${readyInstances.size} ready ${healthyInstances.size} healthy need ${nrToStart.value}")
    checkFinished()
  }

  def checkFinished(): Unit = {
    nrToStart.foreach{ n =>
      if (targetCountReached(n)) success()
    }
  }

  val nrToStart: Future[Int] = async {
    val instances = await(instanceTracker.specInstances(runSpec.id))
    val alreadyLaunched = instances.count { i => i.isActive || i.isScheduled }
    val target = Math.max(0, scaleTo - alreadyLaunched)
    logger.info(s"TaskStartActor about to start $target instances. $alreadyLaunched already launched, $scaleTo is target count")
    target
  }.pipeTo(self)

  def initializeStart(): Future[Done] = async {
    val toStart = await(nrToStart)
    logger.info(s"TaskStartActor: initializing for ${runSpec.id} and toStart: $toStart")
    if (toStart > 0) await(launchQueue.add(runSpec, toStart))
    else Done
  }.pipeTo(self)

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  def success(): Unit = {
    logger.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
    // Since a lot of actor's code happens asynchronously now
    // it can happen that this promise might succeed twice.
    promise.trySuccess(())
    context.stop(self)
  }
}

object TaskStartActor {

  import scala.concurrent.duration._

  case object Sync
  case object PostStart

  val firstSyncDelay = 1.seconds
  val syncInterval = 5.seconds

  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new TaskStartActor(deploymentManager, status, launchQueue, instanceTracker,
      eventBus, readinessCheckExecutor, runSpec, scaleTo, promise)
    )
  }
}
