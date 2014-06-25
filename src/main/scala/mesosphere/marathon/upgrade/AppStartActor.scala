package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor._
import mesosphere.marathon.event.{ HealthStatusChanged, MarathonHealthCheckEvent, MesosStatusUpdateEvent }
import akka.actor.Actor.Receive

class AppStartActor(
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    val eventBus: EventStream,
    val app: AppDefinition,
    scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging with StartingBehavior {

  val expectedSize = scaleTo
  def withHealthChecks = app.healthChecks.nonEmpty

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
    promise.success(())
    context.stop(self)
  }
}
