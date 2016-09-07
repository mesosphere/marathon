package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.state.RunnableSpec
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
    val runSpec: RunnableSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val nrToStart: Int = scaleTo

  def initializeStart(): Unit = {
    // TODO (pods): there's no copy on a trait. we could pass the scaleTo instead
    scheduler.startApp(driver, runSpec.copy(instances = scaleTo))
  }

  override def postStop(): Unit = {
    import mesosphere.marathon.state.RunnableSpec._
    eventBus.unsubscribe(self)
    if (!promise.isCompleted) {
      if (promise.tryFailure(new AppStartCanceledException("The runSpec start has been cancelled"))) {
        scheduler.stopApp(runSpec).onFailure {
          case NonFatal(e) => log.error(s"while stopping runSpec ${runSpec.id}", e)
        }(context.dispatcher)
      }
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
  //scalastyle:off
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    taskTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunnableSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new AppStartActor(deploymentManager, status, driver, scheduler, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, runSpec, scaleTo, promise))
  }
}
