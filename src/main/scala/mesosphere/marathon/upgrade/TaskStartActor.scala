package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import mesosphere.marathon.{ SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver

import scala.concurrent.Promise

class TaskStartActor(
    val driver: SchedulerDriver,
    val scheduler: SchedulerActions,
    val taskQueue: TaskQueue,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    val app: AppDefinition,
    nrToStart: Int,
    val withHealthChecks: Boolean,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  var running: Int = 0

  override def initializeStart(): Unit = {
    for (_ <- 0 until nrToStart) taskQueue.add(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  override def expectedSize: Int = nrToStart

  override def success(): Unit = {
    log.info(s"Successfully started $nrToStart instances of ${app.id}")
    promise.success(())
    context.stop(self)
  }

}
