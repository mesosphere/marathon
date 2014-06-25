package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor._
import mesosphere.marathon.event.{ HealthStatusChanged, MarathonHealthCheckEvent, MesosStatusUpdateEvent }

class AppStartActor(
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    eventBus: EventStream,
    app: AppDefinition,
    scaleTo: Int,
    promise: Promise[Unit]) extends Actor with ActorLogging {

  var healthyTasks = Set.empty[String]
  var runningTasks = 0
  val Version = app.version.toString

  override def preStart(): Unit = {
    if (app.healthChecks.nonEmpty) {
      eventBus.subscribe(self, classOf[MarathonHealthCheckEvent])
    }
    else {
      eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    }

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

  def receive =
    if (app.healthChecks.nonEmpty) checkForHealthy
    else checkForRunning

  def checkForHealthy: Receive = {
    case HealthStatusChanged(app.`id`, taskId, true, _, _) if !healthyTasks(taskId) =>
      healthyTasks += taskId
      if (healthyTasks.size == scaleTo) {
        success()
      }

    case x => log.debug(s"Received $x")
  }

  def checkForRunning: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", app.`id`, _, _, Version, _, _) =>
      runningTasks += 1
      if (runningTasks == scaleTo) {
        success()
      }

    case x => log.debug(s"Received $x")
  }

  def success(): Unit = {
    // all the apps are healthy, so we're done
    promise.success(())
    context.stop(self)
  }
}
