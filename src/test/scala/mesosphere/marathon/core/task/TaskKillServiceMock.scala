package mesosphere.marathon.core.task

import akka.Done
import akka.actor.ActorSystem
import mesosphere.marathon.core.event.MesosStatusUpdateEvent
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }

import scala.collection.mutable
import scala.concurrent.Future

/**
  * A Mocked TaskKillService that publishes a TASK_KILLED event for each given task and always works successfully
  */
class TaskKillServiceMock(system: ActorSystem) extends TaskKillService {

  var numKilled = 0
  val customStatusUpdates = mutable.Map.empty[Instance.Id, MesosStatusUpdateEvent]
  val killed = mutable.Set.empty[Instance.Id]

  override def killTasks(tasks: Iterable[Instance], reason: TaskKillReason): Future[Done] = {
    tasks.foreach { task =>
      killTaskById(task.id, reason)
    }
    Future.successful(Done)
  }
  private[this] def killTaskById(taskId: Instance.Id, reason: TaskKillReason): Future[Done] = {
    val appId = taskId.runSpecId
    val update = customStatusUpdates.getOrElse(taskId, MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", appId, "", None, Nil, "no-version"))
    system.eventStream.publish(update)
    numKilled += 1
    killed += taskId
    Future.successful(Done)
  }

  override def killTask(task: Instance, reason: TaskKillReason): Future[Done] = killTaskById(task.id, reason)

  override def killUnknownTask(taskId: Instance.Id, reason: TaskKillReason): Future[Done] = killTaskById(taskId, reason)
}

