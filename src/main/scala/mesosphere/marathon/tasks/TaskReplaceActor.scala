package mesosphere.marathon.tasks

import akka.actor.{ActorRef, Actor}
import com.google.common.eventbus.{Subscribe, EventBus}
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.Promise
import mesosphere.marathon.event.HealthStatusChanged
import org.apache.mesos.SchedulerDriver
import scala.collection.mutable
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeFailedException
import org.slf4j.LoggerFactory

class EventForwarder(receiver: ActorRef) {
  @Subscribe
  def forward(event: HealthStatusChanged) = receiver ! event
}

class TaskReplaceActor(
  driver: SchedulerDriver,
  eventBus: EventBus,
  appId: String,
  nrToStart: Int,
  tasks: Set[MarathonTask],
  promise: Promise[Boolean]
) extends Actor {

  private [this] val log = LoggerFactory.getLogger(getClass)

  var forwarder: Option[EventForwarder] = None
  var healthy = Set.empty[String]
  var taskIds = tasks.map(_.getId)
  val toKill = taskIds.to[mutable.Queue]

  override def preStart() {
    forwarder = Some(new EventForwarder(self))
    forwarder foreach eventBus.register
  }

  override def postStop() {
    forwarder foreach eventBus.unregister
  }

  def receive : Receive = {
    case HealthStatusChanged(`appId`, taskId, true, _) =>
      healthy += taskId
      if (toKill.nonEmpty)
        driver.killTask(buildTaskId(toKill.dequeue()))
      if (healthy.size == nrToStart) {
        promise.success(true)
      }

    case HealthStatusChanged(_, taskId, false, _) if taskIds(taskId) && healthy(taskId) =>
      val msg = s"Task $taskId went from a healthy to un unhealthy state during replacement"
      promise.failure(new TaskUpgradeFailedException(msg))
      context.stop(self)

    case x: HealthStatusChanged =>
      log.debug(s"Received $x")
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}
