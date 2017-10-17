package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.concurrent.{ Future, Promise }

class AppStartActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val scheduler: SchedulerActions,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    currentInstances: Seq[Instance],
    promise: Promise[Unit]) extends Actor with StartingBehavior with StrictLogging {

  override val nrToStart = Future.successful(scaleTo)

  override def initializeStart(): Future[Done] = {
    // In case we already have running instances (can happen on master abdication during deployment)
    // with the correct version those will not be killed.
    val runningInstances = currentInstances.count(_.isActive)
    scheduler.startRunSpec(runSpec.withInstances(Math.max(runningInstances, scaleTo)))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  def success(): Unit = {
    logger.info(s"Successfully started $scaleTo instances of ${runSpec.id}")
    // Since a lot of StartingBehavior and this actor's code happens asynchronously now
    // it can happen that this promise might succeed twice.
    promise.trySuccess(())
    context.stop(self)
  }
}

object AppStartActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    taskTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    currentInstances: Seq[Instance],
    promise: Promise[Unit]): Props = {
    Props(new AppStartActor(deploymentManager, status, scheduler, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, runSpec, scaleTo, currentInstances, promise))
  }
}
