package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.event.EventStream
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.Promise

class AppStopActor(
    val driver: SchedulerDriver,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    app: AppDefinition,
    val config: UpgradeConfig,
    val promise: Promise[Unit]) extends StoppingBehavior {

  override var idsToKill: mutable.Set[Task.Id] = taskTracker.appTasksLaunchedSync(app.id).map(_.taskId).to[mutable.Set]

  override def appId: PathId = app.id

}

object AppStopActor {
  def props(
    driver: SchedulerDriver,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    config: UpgradeConfig,
    promise: Promise[Unit]): Props = Props(new AppStopActor(driver, taskTracker, eventBus, app, config, promise))
}
