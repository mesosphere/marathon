package mesosphere.marathon.upgrade

import akka.actor.{ActorLogging, Actor}
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.Promise
import mesosphere.marathon.event.{MesosStatusUpdateEvent, HealthStatusChanged}
import org.apache.mesos.SchedulerDriver
import scala.collection.mutable
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.{TaskUpgradeCancelledException, TaskFailedException}
import akka.event.EventStream

class TaskReplaceActor(
  driver: SchedulerDriver,
  eventBus: EventStream,
  appId: String,
  version: String,
  nrToStart: Int,
  tasksToKill: Set[MarathonTask],
  promise: Promise[Boolean]
) extends Actor with ActorLogging {

  eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
  eventBus.subscribe(self, classOf[HealthStatusChanged])

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCancelledException(
          "The task upgrade has been cancelled"))
  }

  var healthy = Set.empty[String]
  var taskIds = tasksToKill.map(_.getId)
  val toKill = taskIds.to[mutable.Queue]

  def receive : Receive = {
    case HealthStatusChanged(`appId`, taskId, true, _, _) =>
      healthy += taskId
      if (toKill.nonEmpty) {
        val killing = toKill.dequeue()
        log.info(s"Killing old task $killing because $taskId became reachable")
        driver.killTask(buildTaskId(killing))
      }
      if (healthy.size == nrToStart) {
        promise.success(true)
        context.stop(self)
      }

    case MesosStatusUpdateEvent(slaveId, taskId, "TASK_FAILED", `appId`, _, _, `version`, _, _) =>
      val msg = s"Task $taskId failed on slave $slaveId"
      log.error(msg)
      promise.failure(new TaskFailedException(msg))
      context.stop(self)

    case x => log.debug(s"Received $x")
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}
