package mesosphere.marathon.event

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.state.{ PathId, TaskFailureEventRepository }

class HistoryActor(eventBus: EventStream, taskFailureEventRepository: TaskFailureEventRepository)
    extends Actor with ActorLogging {

  override def preStart() = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[AppTerminatedEvent])
  }

  val taskFailureStatus = "^TASK_(LOST|KILLED)$".r
  def receive = {
    case MesosStatusUpdateEvent(slaveId, taskId, taskFailureStatus(taskStatus), statusMessage, appId, host, ports, version, eventType, timestamp) =>
      // TODO(michaeljin): add taskId, version, host
      // TODO(michaeljin): Make length of history configurable
      taskFailureEventRepository.store(appId, TaskFailureEvent(appId, statusMessage, timestamp = timestamp))

    case AppTerminatedEvent(appId, eventType, timestamp) =>
      taskFailureEventRepository.expunge(appId)
  }

  def getLastFailure(appId: PathId) = {
    taskFailureEventRepository.current(appId)
  }
}

object HistoryActor {
  sealed trait Event
  sealed trait Command {
    def answer: Event
  }
}