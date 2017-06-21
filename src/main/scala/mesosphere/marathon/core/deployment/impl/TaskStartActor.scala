package mesosphere.marathon
package core.deployment.impl

import akka.actor.{ Actor, ActorRef, Props }
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.concurrent.Promise

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

  val nrToStart: Int = Math.max(
    0,
    scaleTo - launchQueue.get(runSpec.id).map(_.finalInstanceCount)
      .getOrElse(instanceTracker.countLaunchedSpecInstancesSync(runSpec.id)))

  def initializeStart(): Unit = if (nrToStart > 0) launchQueue.add(runSpec, nrToStart)

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def success(): Unit = {
    logger.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
    promise.success(())
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
