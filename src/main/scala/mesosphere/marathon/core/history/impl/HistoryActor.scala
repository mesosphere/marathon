package mesosphere.marathon
package core.history.impl

import akka.actor.Actor
import akka.event.EventStream
import mesosphere.marathon.core.event._
import mesosphere.marathon.state.TaskFailure
import mesosphere.marathon.storage.repository.TaskFailureRepository

// TODO(PODS): Move from Task to Instance
class HistoryActor(eventBus: EventStream, taskFailureRepository: TaskFailureRepository)
  extends Actor {

  override def preStart(): Unit = {
    // TODO(cleanup): adjust InstanceChanged to be able to replace using MesosStatusUpdateEvent here (#4792)
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[UnhealthyInstanceKillEvent])
    eventBus.subscribe(self, classOf[AppTerminatedEvent])
  }

  def receive: Receive = {

    case TaskFailure.FromUnhealthyInstanceKillEvent(taskFailure) =>
      taskFailureRepository.store(taskFailure)

    case TaskFailure.FromMesosStatusUpdateEvent(taskFailure) =>
      taskFailureRepository.store(taskFailure)

    case _: MesosStatusUpdateEvent => // ignore non-failure status updates

    case AppTerminatedEvent(appId, eventType, timestamp) =>
      taskFailureRepository.delete(appId)
  }
}
