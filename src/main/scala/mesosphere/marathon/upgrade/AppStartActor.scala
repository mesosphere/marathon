package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.concurrent.Promise

class AppStartActor(
    val driver: SchedulerDriver,
    val scheduler: SchedulerActions,
    val taskQueue: TaskQueue,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    val app: AppDefinition,
    scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val expectedSize = scaleTo

  def withHealthChecks: Boolean = app.healthChecks.nonEmpty

  def initializeStart(): Unit = {
    scheduler.startApp(driver, app.copy(instances = scaleTo))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted) {
      if (promise.tryFailure(new AppStartCanceledException("The app start has been cancelled"))) {
        scheduler.stopApp(driver, app)
      }
    }
  }

  def success(): Unit = {
    log.info(s"Successfully started $scaleTo instances of ${app.id}")
    promise.success(())
    context.stop(self)
  }
}
