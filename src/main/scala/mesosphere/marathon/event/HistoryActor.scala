package mesosphere.marathon.event

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.state.{ TaskOffersDeclinedRepository, TaskFailure, TaskFailureRepository }

class HistoryActor(eventBus: EventStream,
                   taskFailureRepository: TaskFailureRepository,
                   taskOfferDeclinedRepository: TaskOffersDeclinedRepository)
    extends Actor with ActorLogging {

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[AppTerminatedEvent])
    eventBus.subscribe(self, classOf[TaskOfferDeclinedEvent])
  }

  def receive: Receive = {
    case TaskFailure.FromMesosStatusUpdateEvent(taskFailure) =>
      taskFailureRepository.store(taskFailure.appId, taskFailure)

    case _: MesosStatusUpdateEvent => // ignore non-failure status updates

    case AppTerminatedEvent(appId, eventType, timestamp) =>
      taskFailureRepository.expunge(appId)

    case e: TaskOfferDeclinedEvent => taskOfferDeclinedRepository.store(e.appId, e)
  }

}

