package mesosphere.marathon.core.history

import akka.actor.{ ActorSystem, Props }
import akka.event.EventStream
import mesosphere.marathon.core.history.impl.HistoryActor
import mesosphere.marathon.state.TaskFailureRepository

class HistoryModule(
    eventBus: EventStream,
    actorSystem: ActorSystem,
    taskFailureRepository: TaskFailureRepository) {
  lazy val historyActorProps: Props = Props(new HistoryActor(eventBus, taskFailureRepository))
}
