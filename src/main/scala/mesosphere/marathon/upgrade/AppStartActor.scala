package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec
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
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val nrToStart: Int = scaleTo

  def initializeStart(): Unit = {
    scheduler.startRunSpec(runSpec.withInstances(scaleTo))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted && promise.tryFailure(new AppStartCanceledException("The app start has been cancelled"))) {
      scheduler.stopRunSpec(runSpec).onFailure {
        case NonFatal(e) => log.error(s"while stopping app ${runSpec.id}", e)
      }(context.dispatcher)
    }
    super.postStop()
  }

  def success(): Unit = {
    log.info(s"Successfully started $scaleTo instances of ${runSpec.id}")
    promise.success(())
    context.stop(self)
  }
}

object AppStartActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    taskTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new AppStartActor(deploymentManager, status, driver, scheduler, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, runSpec, scaleTo, promise))
  }
}
