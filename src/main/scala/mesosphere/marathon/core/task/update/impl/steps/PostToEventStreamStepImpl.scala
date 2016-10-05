package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event.InstanceHealthChanged
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (eventBus: EventStream, clock: Clock) extends InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def process(update: InstanceChange): Future[Done] = {
    log.info("Publishing events for {} of runSpec [{}]: {}", update.id, update.runSpecId, update.status)
    update.events.foreach(eventBus.publish)

    // TODO(PODS): this can be generated in InstanceChangedEventsGenerator as well
    if (update.lastState.flatMap(_.healthy) != update.instance.state.healthy) {
      eventBus.publish(InstanceHealthChanged(update.id, update.runSpecVersion,
        update.runSpecId, update.instance.state.healthy))
    }

    Future.successful(Done)
  }
}
