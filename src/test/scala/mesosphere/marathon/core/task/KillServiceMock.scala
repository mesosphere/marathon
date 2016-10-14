package mesosphere.marathon.core.task

import akka.Done
import akka.actor.ActorSystem
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.test.Mockito

import scala.collection.mutable
import scala.concurrent.Future

/**
  * A Mocked KillService that publishes a TASK_KILLED event for each given task and always works successfully
  */
class KillServiceMock(system: ActorSystem) extends KillService with Mockito {

  var numKilled = 0
  val customStatusUpdates = mutable.Map.empty[Instance.Id, Seq[MarathonEvent]]
  val killed = mutable.Set.empty[Instance.Id]
  val eventsGenerator = InstanceChangedEventsGenerator
  val clock = ConstantClock()

  override def killInstances(instances: Iterable[Instance], reason: KillReason): Future[Done] = {
    instances.foreach { instance =>
      killInstance(instance, reason)
    }
    Future.successful(Done)
  }
  override def killInstance(instance: Instance, reason: KillReason): Future[Done] = {
    val id = instance.instanceId
    val events = customStatusUpdates.getOrElse(id, eventsGenerator.events(Condition.Killed, instance, task = None, now = clock.now()))
    events.foreach(system.eventStream.publish)
    numKilled += 1
    killed += id
    Future.successful(Done)
  }

  override def killUnknownTask(taskId: Id, reason: KillReason): Unit = {
    val instance = mock[Instance]
    instance.instanceId returns taskId.instanceId
    killInstance(instance, reason)
  }
}

