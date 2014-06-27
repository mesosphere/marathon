package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.{ AppStopCanceledException, SchedulerActions }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.tasks.TaskTracker
import scala.collection.immutable.Set

class AppStopActor(
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ActorLogging {

  var idsToKill = taskTracker.fetchApp(app.id).tasks.map(_.getId).to[Set]

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    scheduler.stopApp(driver, app)
    checkFinished()
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(new AppStopCanceledException("The app stop has been cancelled"))
  }

  val taskFinished = "^TASK_(FINISHED|LOST|KILLED)$".r

  def receive = {
    case MesosStatusUpdateEvent(_, taskId, taskFinished(_), _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill -= taskId
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case x => log.debug(s"Received $x")
  }

  def checkFinished(): Unit = {
    if (idsToKill.isEmpty) {
      promise.success(())
      context.stop(self)
    }
  }
}
