package mesosphere.marathon.upgrade

import mesosphere.marathon.tasks.TaskQueue
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor.{ ActorLogging, Actor }
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.{ TaskUpgradeCanceledException, TaskFailedException }

class TaskStartActor(
    taskQueue: TaskQueue,
    val eventBus: EventStream,
    val app: AppDefinition,
    nrToStart: Int,
    val withHealthChecks: Boolean,
    promise: Promise[Boolean]) extends Actor with ActorLogging with StartingBehavior {

  var running: Int = 0
  val AppID = app.id

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
    promise.success(true)
    context.stop(self)
  }

}
