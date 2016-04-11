package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.DeploymentStatus
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.concurrent.Promise
import scala.util.control.NonFatal

class AppStartActor(
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

  val nrToStart: Int = scaleTo

  def initializeStart(): Unit = {
    scheduler.startApp(driver, app.copy(instances = scaleTo))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted) {
      if (promise.tryFailure(new AppStartCanceledException("The app start has been cancelled"))) {
        scheduler.stopApp(driver, app).onFailure {
          case NonFatal(e) => log.error(s"while stopping app ${app.id}", e)
        }(context.dispatcher)
      }
    }
    super.postStop()
  }

  def success(): Unit = {
    log.info(s"Successfully started $scaleTo instances of ${app.id}")
    promise.success(())
    context.stop(self)
  }
}

object AppStartActor {
  //scalastyle:off
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
    Props(new AppStartActor(deploymentManager, status, driver, scheduler, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, app, scaleTo, promise))
  }
}
