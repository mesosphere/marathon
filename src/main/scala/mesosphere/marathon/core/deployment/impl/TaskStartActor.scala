package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.pattern._
import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.state.RunSpec

import scala.async.Async.{async, await}
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

class TaskStartActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val scheduler: scheduling.Scheduler,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with StrictLogging with StartingBehavior {

  override val nrToStart: Future[Int] = async {
    val instances = await(scheduler.getInstances(runSpec.id))
    val alreadyLaunched = instances.count { i => i.isActive || i.isScheduled }
    val target = Math.max(0, scaleTo - alreadyLaunched)
    logger.info(s"TaskStartActor about to start $target instances. $alreadyLaunched already launched, $scaleTo is target count")
    target
  }.pipeTo(self)

  override def initializeStart(): Future[Done] = async {
    val toStart = await(nrToStart)
    logger.info(s"TaskStartActor: initializing for ${runSpec.id} and toStart: $toStart")
    if (toStart > 0) {
      await(scheduler.schedule(runSpec, toStart))
      Done
    } else Done
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
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    scheduler: scheduling.Scheduler,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new TaskStartActor(deploymentManager, status, scheduler, eventBus, readinessCheckExecutor, runSpec, scaleTo, promise)
    )
  }
}
