package mesosphere.marathon
package upgrade

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.Done
import akka.pattern._
import akka.event.EventStream
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.{ SchedulerActions, TaskUpgradeCanceledException }

import scala.async.Async.{ async, await }
import scala.concurrent.{ Future, Promise }
import scala.concurrent.ExecutionContext.Implicits.global

@SuppressWarnings(Array("all")) // async/await
class TaskStartActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val scheduler: SchedulerActions,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  override val nrToStart: Future[Int] = async {
    val alreadyLaunched = await(launchQueue.getAsync(runSpec.id)) match {
      case Some(info) => info.finalInstanceCount
      case None => await(instanceTracker.countLaunchedSpecInstances(runSpec.id))
    }
    Math.max(0, scaleTo - alreadyLaunched)
  }.pipeTo(self)

  @SuppressWarnings(Array("all")) // async/await
  override def initializeStart(): Future[Done] = async {
    val toStart = await(nrToStart)
    if (toStart > 0) await(launchQueue.addAsync(runSpec, toStart))
    else Done
  }.pipeTo(self)

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def success(): Unit = {
    log.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
    // Since a lot of StartingBehavior and this actor's code happens asynchronously now
    // it can happen that this promise might succeed twice.
    promise.trySuccess(())
    context.stop(self)
  }

  override def shutdown(): Unit = {
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
    context.stop(self)
  }
}

object TaskStartActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new TaskStartActor(deploymentManager, status, scheduler, launchQueue, instanceTracker,
      eventBus, readinessCheckExecutor, runSpec, scaleTo, promise)
    )
  }
}
