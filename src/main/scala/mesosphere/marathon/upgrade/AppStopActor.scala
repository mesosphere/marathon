package mesosphere.marathon.upgrade

import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.Promise

class AppStopActor(
    val driver: SchedulerDriver,
    scheduler: SchedulerActions,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    app: AppDefinition,
    val promise: Promise[Unit]) extends StoppingBehavior {

  var idsToKill: mutable.Set[String] = taskTracker.get(app.id).map(_.getId).to[mutable.Set]

  def appId: PathId = app.id

  def initializeStop(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    scheduler.stopApp(driver, app)
  }
}
