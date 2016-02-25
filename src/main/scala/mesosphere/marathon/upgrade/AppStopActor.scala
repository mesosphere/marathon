package mesosphere.marathon.upgrade

import akka.event.EventStream
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.Promise

class AppStopActor(
    val driver: SchedulerDriver,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    app: AppDefinition,
    val promise: Promise[Unit]) extends StoppingBehavior {

  override var idsToKill: mutable.Set[Task.Id] = taskTracker.appTasksLaunchedSync(app.id).map(_.taskId).to[mutable.Set]

  def appId: PathId = app.id

  def initializeStop(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    for (id <- idsToKill) driver.killTask(id.mesosTaskId)
  }
}
