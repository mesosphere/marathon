package mesosphere.marathon.core.task.update.impl.steps
//scalastyle:off
import akka.Done
import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event.{ InstanceChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Future
//scalastyle:on
/**
  * Post this update to the internal event stream.
  */
class PostToEventStreamStepImpl @Inject() (eventBus: EventStream, clock: Clock)
    extends TaskUpdateStep with InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "postTaskStatusEvent"

  override def process(update: InstanceChange): Future[Done] = {
    log.info("Sending instance change event for {} of runSpec [{}]: {}", update.id, update.runSpecId, update.status)

    eventBus.publish(
      InstanceChanged(update.id, update.runSpecVersion, update.runSpecId, update.status, update.instance))

    // for backwards compatibility, send MesosStatusUpdateEvents for all tasks (event if they didn't change):
    update.instance.tasksMap.values.iterator.foreach { task =>
      val maybeStatus = task.status.mesosStatus
      val taskId = task.taskId
      val slaveId = maybeStatus.fold("n/a")(_.getSlaveId.getValue)
      val message = maybeStatus.fold("")(status => if (status.hasMessage) status.getMessage else "")
      val host = task.agentInfo.host
      val ipAddresses = maybeStatus.flatMap(status => Task.MesosStatus.ipAddresses(status))
      val ports = task.launched.fold(Seq.empty[Int])(_.hostPorts)
      val timestamp = clock.now()

      eventBus.publish(
        MesosStatusUpdateEvent(
          slaveId,
          taskId,
          // TODO if we posted the MarathonTaskStatus.toString, consumers would not get "TASK_STAGING", but "Staging"
          task.status.taskStatus.toMesosStateName,
          message,
          appId = taskId.runSpecId,
          host,
          ipAddresses,
          ports = ports,
          version = update.instance.runSpecVersion.toString,
          timestamp = timestamp.toString
        )
      )
    }

    Future.successful(Done)
  }

  override def processUpdate(taskChanged: TaskChanged): Future[_] = ???
}
