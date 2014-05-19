package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import akka.event.EventStream
import mesosphere.marathon.Protos.MarathonTask
import akka.actor.{Actor, ActorLogging}
import scala.concurrent.Promise
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.apache.mesos.Protos.TaskID

class TaskKillActor(
  driver: SchedulerDriver,
  eventBus: EventStream,
  tasksToKill: Set[MarathonTask],
  promise: Promise[Boolean]
) extends Actor with ActorLogging {

  val idsToKill = tasksToKill.map(_.getId)
  var count = tasksToKill.size

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    log.info(s"Killing ${tasksToKill.size} instances")
    for (task <- tasksToKill)
      driver.killTask(taskId(task.getId))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
  }

  def receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_KILLED", _, _, _, _, _, _) if idsToKill(taskId) =>
      count -= 1
      if (count == 0) {
        promise.success(true)
        context.stop(self)
      }

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  private def taskId(id: String) = TaskID.newBuilder().setValue(id).build()
}
