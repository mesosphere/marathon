package mesosphere.marathon.upgrade

import akka.actor.{ Props, ActorRef, Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.DeploymentStatus
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.{ SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver

import scala.concurrent.Promise

class TaskStartActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val driver: SchedulerDriver,
    val scheduler: SchedulerActions,
    val launchQueue: LaunchQueue,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val app: AppDefinition,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val nrToStart: Int =
    scaleTo - launchQueue.get(app.id).map(_.finalTaskCount).getOrElse(taskTracker.countLaunchedAppTasksSync(app.id))

  override def initializeStart(): Unit = {
    if (nrToStart > 0)
      launchQueue.add(app, nrToStart)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
    super.postStop()
  }

  override def success(): Unit = {
    log.info(s"Successfully started $nrToStart instances of ${app.id}")
    promise.success(())
    context.stop(self)
  }
}

//scalastyle:off
object TaskStartActor {
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: AppDefinition,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new TaskStartActor(deploymentManager, status, driver, scheduler, launchQueue, taskTracker,
      eventBus, readinessCheckExecutor, app, scaleTo, promise)
    )
  }
}
