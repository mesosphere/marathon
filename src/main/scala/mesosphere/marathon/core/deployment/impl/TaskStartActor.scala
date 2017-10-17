package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.pattern._
import akka.actor.{ Actor, ActorRef, Props }
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.async.Async.{ async, await }
import scala.concurrent.{ Future, Promise }
import mesosphere.marathon.core.async.ExecutionContexts.global

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
    promise: Promise[Unit]) extends Actor with StrictLogging with StartingBehavior {

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
    logger.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
    // Since a lot of StartingBehavior and this actor's code happens asynchronously now
    // it can happen that this promise might succeed twice.
    promise.trySuccess(())
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
