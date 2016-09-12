package mesosphere.marathon.core.task

import akka.Done
import akka.actor.ActorSystem
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.instance.{ InstanceStatus, Instance }
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.Mockito

import scala.collection.mutable
import scala.concurrent.Future

/**
  * A Mocked TaskKillService that publishes a TASK_KILLED event for each given task and always works successfully
  */
class TaskKillServiceMock(system: ActorSystem) extends TaskKillService with Mockito {

  var numKilled = 0
  val customStatusUpdates = mutable.Map.empty[Instance.Id, InstanceChanged]
  val killed = mutable.Set.empty[Instance.Id]

  override def killTasks(instances: Iterable[Instance], reason: TaskKillReason): Future[Done] = {
    instances.foreach { instance =>
      killTask(instance, reason)
    }
    Future.successful(Done)
  }
  override def killTask(instance: Instance, reason: TaskKillReason): Future[Done] = {
    val id = instance.id
    val runSpecId = id.runSpecId
    //val update = customStatusUpdates.getOrElse(instanceId, MesosStatusUpdateEvent("", instanceId, "TASK_KILLED", "", appId, "", None, Nil, "no-version"))
    val update = customStatusUpdates.getOrElse(id, InstanceChanged(id, Timestamp.now(), runSpecId, InstanceStatus.Killed, instance))
    system.eventStream.publish(update)
    numKilled += 1
    killed += id
    Future.successful(Done)
  }

  override def killUnknownTask(instanceId: Instance.Id, reason: TaskKillReason): Future[Done] = {
    val instance = mock[Instance]
    instance.id returns instanceId
    killTask(instance, reason)
  }
}

