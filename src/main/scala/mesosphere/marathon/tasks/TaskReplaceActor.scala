package mesosphere.marathon.tasks

import akka.actor.{ActorRef, Actor}
import com.google.common.eventbus.{Subscribe, EventBus}
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.Promise
import mesosphere.marathon.event.{MesosStatusUpdateEvent, HealthStatusChanged}
import org.apache.mesos.SchedulerDriver
import scala.collection.mutable
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeFailedException
import org.slf4j.LoggerFactory

class EventForwarder(receiver: ActorRef) {
  @Subscribe
  def forward(event: HealthStatusChanged) = receiver ! event

  @Subscribe
  def forward(event: MesosStatusUpdateEvent) = receiver ! event
}

class TaskReplaceActor(
  driver: SchedulerDriver,
  eventBus: EventBus,
  appId: String,
  nrToStart: Int,
  tasksToKill: Set[MarathonTask],
  promise: Promise[Boolean]
) extends Actor {

  private [this] val log = LoggerFactory.getLogger(getClass)

  var healthy = Set.empty[String]
  var taskIds = tasksToKill.map(_.getId)
  val toKill = taskIds.to[mutable.Queue]

  def receive : Receive = {
    case HealthStatusChanged(`appId`, taskId, true, _) =>
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

    case HealthStatusChanged(_, taskId, false, _) if taskIds(taskId) && healthy(taskId) =>
      val msg = s"Task $taskId went from a healthy to un unhealthy state during replacement"
      promise.failure(new TaskUpgradeFailedException(msg))
      context.stop(self)

    case MesosStatusUpdateEvent(slaveId, taskId, "TASK_FAILED", `appId`, _, _, _) =>
      val msg = s"Task $taskId failed on slave $slaveId"
      log.error(msg)
      promise.failure(new TaskUpgradeFailedException(msg))
      context.stop(self)

    case x =>
      log.debug(s"Received $x")
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}
