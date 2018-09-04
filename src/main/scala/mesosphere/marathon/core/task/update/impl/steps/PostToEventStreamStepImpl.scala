package mesosphere.marathon
package core.task.update.impl.steps

import akka.Done
import akka.event.EventStream
import com.google.inject.Inject
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.InstanceHealthChanged
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}

import scala.concurrent.Future

/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (eventBus: EventStream) extends InstanceChangeHandler with StrictLogging {

  override def name: String = "postTaskStatusEvent"
  override def metricName: String = "post-task-status-event"

  override def process(update: InstanceChange): Future[Done] = {
    logger.debug("Publishing events for {} of runSpec [{}]: {}", update.id, update.runSpecId, update.condition)
    update.events.foreach(eventBus.publish)

    // TODO(PODS): this can be generated in InstanceChangedEventsGenerator as well
    if (update.lastState.flatMap(_.healthy) != update.instance.state.healthy) {
      eventBus.publish(InstanceHealthChanged(update.id, update.runSpecVersion,
        update.runSpecId, update.instance.state.healthy))
    }

    Future.successful(Done)
  }
}
