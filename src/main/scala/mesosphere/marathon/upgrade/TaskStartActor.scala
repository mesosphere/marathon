package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp }
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import mesosphere.marathon.{ SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver

import scala.concurrent.{ Future, Promise }

class TaskStartActor(
    val appRepository: AppRepository,
    val driver: SchedulerDriver,
    val scheduler: SchedulerActions,
    val taskQueue: TaskQueue,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    val app: AppDefinition,
    val scaleTo: Int,
    val withHealthChecks: Boolean,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val nrToStart: Int = scaleTo - taskQueue.count(app.id) - taskTracker.count(app.id)

  override def initializeStart(): Unit = {
    if (nrToStart > 0)
      taskQueue.add(app, nrToStart)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  override def success(): Unit = {
    // update old tasks which only differ in version and instances
    import context.dispatcher
    val oldTasks = taskTracker.get(app.id).filter(_.getVersion != app.version.toString)
    val scaledOldTasks = oldTasks.map { oldTask =>
      appRepository.app(app.id, Timestamp(oldTask.getVersion)) collect {
        case Some(scaledOldApp) if scaledOldApp.copy(version=app.version, instances=app.instances) == app =>
          taskTracker.scaled(app.id, oldTask.getId, app.version.toString)
      }
    }

    Future.sequence(scaledOldTasks) map { _ =>
      log.info(s"Successfully started $nrToStart instances of ${app.id}")
      promise.success(())
      context.stop(self)
    }
  }

}
