package mesosphere.marathon
package core.task.tracker.impl

import java.time.Clock
import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.{Actor, ActorRef, Props, Status}
import akka.event.LoggingReceive
import akka.pattern.pipe
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOpResolver}
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.UpdateContext
import mesosphere.marathon.core.task.tracker.impl.InstanceUpdateActor.{ActorMetrics, FinishedUpdate, QueuedUpdate}
import mesosphere.marathon.metrics.{Counter, Metrics, SettableGauge, Timer}
import mesosphere.marathon.metrics.deprecated.ServiceMetric

import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object InstanceUpdateActor {
  def props(clock: Clock, metrics: ActorMetrics,
    instanceTrackerRef: ActorRef,
    stateOpResolver: InstanceUpdateOpResolver,
    instanceTrackerQueryTimeout: FiniteDuration): Props = {
    Props(new InstanceUpdateActor(clock, metrics, instanceTrackerRef, stateOpResolver, instanceTrackerQueryTimeout))
  }
  /**
    * Internal message of the [[InstanceUpdateActor]] which indicates that an operation has been processed completely.
    * It might have succeeded or failed.
    */
  private case class FinishedUpdate(queuedUpdate: QueuedUpdate)
  private[impl] case class QueuedUpdate(sender: ActorRef, update: UpdateContext)

  class ActorMetrics(metrics: Metrics) {
    /** the number of ops that are for instances that already have an op ready */
    val oldQueuedOpsMetric: SettableGauge =
      metrics.deprecatedSettableGauge(ServiceMetric, classOf[InstanceUpdateActor], "delayed-ops")
    val newQueuedOpsMetric: SettableGauge =
      metrics.settableGauge("debug.instance-tracker.operations.queued")

    /** the number of currently processed ops */
    val oldActiveOpsMetric: SettableGauge =
      metrics.deprecatedSettableGauge(ServiceMetric, classOf[InstanceUpdateActor], "ready-ops")
    val newActiveOpsMetric: SettableGauge =
      metrics.settableGauge("debug.instance-tracker.operations.ready")

    /** the number of ops that we rejected because of a timeout */
    val oldTimedOutOpsMetric: SettableGauge =
      metrics.deprecatedSettableGauge(ServiceMetric, classOf[InstanceUpdateActor], "ops-timeout")
    val newTimedOutOpsMetric: Counter =
      metrics.counter("debug.instance-tracker.operations.timed-out")

    /** a timer around op processing */
    val oldProcessOpTimeMetric: Timer =
      metrics.deprecatedTimer(ServiceMetric, classOf[InstanceUpdateActor], "process-op")
    val newProcessOpTimeMetric: Timer =
      metrics.timer("debug.instance-tracker.operations.processing-duration")
  }
}

/**
  * This actor serializes [[InstanceTrackerActor.UpdateContext]]s for the same instance. The operations
  * are executed by the given processor.
  *
  * Assumptions:
  * * This actor must be the only one performing instance updates.
  * * This actor is spawned as a child of the [[InstanceTrackerActor]].
  * * Errors in this actor lead to a restart of the InstanceTrackerActor.
  */
private[impl] class InstanceUpdateActor(
    clock: Clock,
    metrics: ActorMetrics,
    instanceTrackerRef: ActorRef,
    stateOpResolver: InstanceUpdateOpResolver,
    instanceTrackerQueryTimeout: FiniteDuration) extends Actor with StrictLogging {

  // this has to be a mutable field because we need to access it in postStop()
  private[impl] var updatesByInstanceId =
    Map.empty[Instance.Id, Queue[QueuedUpdate]].withDefaultValue(Queue.empty)

  override def preStart(): Unit = {
    metrics.oldActiveOpsMetric.setValue(0)
    metrics.newActiveOpsMetric.setValue(0)
    metrics.oldQueuedOpsMetric.setValue(0)
    metrics.newQueuedOpsMetric.setValue(0)

    super.preStart()
  }

  override def postStop(): Unit = {
    super.postStop()

    // Answer all outstanding requests.
    updatesByInstanceId.values.foreach { queue =>
      queue.foreach { item =>
        item.sender ! Status.Failure(new IllegalStateException("InstanceUpdateActor stopped"))
      }
    }

    metrics.oldActiveOpsMetric.setValue(0)
    metrics.newActiveOpsMetric.setValue(0)
    metrics.oldQueuedOpsMetric.setValue(0)
    metrics.newQueuedOpsMetric.setValue(0)
  }

  def receive: Receive = LoggingReceive {
    case update: UpdateContext =>
      val oldQueue: Queue[QueuedUpdate] = updatesByInstanceId(update.instanceId)
      val newQueue = oldQueue :+ QueuedUpdate(sender(), update)
      updatesByInstanceId += update.instanceId -> newQueue
      metrics.oldQueuedOpsMetric.increment()
      metrics.newQueuedOpsMetric.increment()

      if (oldQueue.isEmpty) {
        // start processing the just received operation
        processNextUpdateIfExists(update.instanceId)
      }

    case FinishedUpdate(queuedUpdate) =>
      val update = queuedUpdate.update
      val (dequeued, newQueue) = updatesByInstanceId(update.instanceId).dequeue
      require(dequeued == queuedUpdate)
      if (newQueue.isEmpty)
        updatesByInstanceId -= update.instanceId
      else
        updatesByInstanceId += update.instanceId -> newQueue

      val oldMetricActiveCount = metrics.oldActiveOpsMetric.value() - 1
      val newMetricActiveCount = metrics.newActiveOpsMetric.value() - 1
      val activeCount = Math.max(oldMetricActiveCount, newMetricActiveCount)
      metrics.oldActiveOpsMetric.setValue(activeCount)
      metrics.newActiveOpsMetric.setValue(activeCount)

      val queuedCount = Math.max(metrics.oldQueuedOpsMetric.value(), metrics.newQueuedOpsMetric.value())
      logger.debug(s"Finished processing ${update.op} for app [${update.appId}] and ${update.instanceId} "
        + s"$activeCount active, $queuedCount queued.")

      processNextUpdateIfExists(update.instanceId)

    case Status.Failure(cause) =>
      // escalate this failure to our parent: InstanceTrackerActor
      throw new IllegalStateException("received failure", cause)
  }

  private[this] def processNextUpdateIfExists(instanceId: Instance.Id): Unit = {
    updatesByInstanceId(instanceId).headOption foreach { queuedItem =>
      val op = queuedItem.update

      val oldMetricQueuedCount = metrics.oldQueuedOpsMetric.value() - 1
      val newMetricQueuedCount = metrics.newQueuedOpsMetric.value() - 1
      val queuedCount = Math.max(oldMetricQueuedCount, newMetricQueuedCount)
      metrics.oldQueuedOpsMetric.setValue(queuedCount)
      metrics.newQueuedOpsMetric.setValue(queuedCount)

      val oldMetricActiveCount = metrics.oldActiveOpsMetric.value() + 1
      val newMetricActiveCount = metrics.newActiveOpsMetric.value() + 1
      val activeCount = Math.max(oldMetricActiveCount, newMetricActiveCount)
      metrics.oldActiveOpsMetric.setValue(activeCount)
      metrics.newActiveOpsMetric.setValue(activeCount)

      logger.debug(s"Start processing ${op.op} for app [${op.appId}] and ${op.instanceId}. "
        + s"$activeCount active, $queuedCount queued.")

      import context.dispatcher
      val future = {
        if (op.deadline <= clock.now()) {
          metrics.oldTimedOutOpsMetric.increment()
          metrics.newTimedOutOpsMetric.increment()
          queuedItem.sender ! Status.Failure(
            new TimeoutException(s"Timeout: ${op.op} for app [${op.appId}] and ${op.instanceId}.")
          )
          Future.successful(())
        } else
          metrics.oldProcessOpTimeMetric(metrics.newProcessOpTimeMetric(processUpdate(queuedItem)))
      }.map { _ =>
        logger.debug(s"Finished processing ${op.op} for app [${op.appId}] and ${op.instanceId}")
        FinishedUpdate(queuedItem)
      }
      future.pipeTo(self)(queuedItem.sender)
    }
  }

  private def processUpdate(queuedUpdate: QueuedUpdate)(implicit ec: ExecutionContext): Future[Done] = {
    val update = queuedUpdate.update
    logger.debug(s"Process $update")
    val stateChange = stateOpResolver.resolve(update.op)

    import akka.pattern.ask

    stateChange.flatMap {
      case change @ (_: InstanceUpdateEffect.Expunge | _: InstanceUpdateEffect.Update) =>
        implicit val queryTimeout: Timeout = instanceTrackerQueryTimeout

        val msg = InstanceTrackerActor.StateChanged(InstanceTrackerActor.Ack(queuedUpdate.sender, change))
        logger.debug(s"Notify instance tracker actor: msg=$msg")
        val f = (instanceTrackerRef ? msg).map(_ => Done)
        f.onComplete(_ => logger.debug(s"Stored $change"))
        f

      case change: InstanceUpdateEffect.Failure =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        queuedUpdate.sender ! Status.Failure(change.cause)
        Future.successful(Done)

      case change: InstanceUpdateEffect.Noop =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        queuedUpdate.sender ! change
        Future.successful(Done)
    }
  }
}
