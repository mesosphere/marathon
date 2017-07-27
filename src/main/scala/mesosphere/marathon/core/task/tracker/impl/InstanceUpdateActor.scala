package mesosphere.marathon
package core.task.tracker.impl

import java.time.Clock

import java.util.concurrent.TimeoutException

import akka.actor.{ Actor, Props, Status }
import akka.event.LoggingReceive
import akka.pattern.pipe
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.impl.InstanceUpdateActor.{ ActorMetrics, FinishedInstanceOp, ProcessInstanceOp }
import mesosphere.marathon.metrics._
import org.slf4j.LoggerFactory

import scala.collection.immutable.Queue
import scala.concurrent.Future

object InstanceUpdateActor {
  def props(clock: Clock, metrics: ActorMetrics, processor: InstanceOpProcessor): Props = {
    Props(new InstanceUpdateActor(clock, metrics, processor))
  }

  /** Request that the [[InstanceUpdateActor]] should process the given op. */
  private[impl] case class ProcessInstanceOp(op: InstanceOpProcessor.Operation)
  /**
    * Internal message of the [[InstanceUpdateActor]] which indicates that an operation has been processed completely.
    * It might have succeeded or failed.
    */
  private case class FinishedInstanceOp(op: InstanceOpProcessor.Operation)

  class ActorMetrics {
    /** the number of ops that are for instances that already have an op ready */
    val numberOfQueuedOps: SettableGauge = Metrics.atomicGauge(ServiceMetric, classOf[InstanceUpdateActor], "delayed-ops")

    /** the number of currently processed ops */
    val numberOfActiveOps: SettableGauge = Metrics.atomicGauge(ServiceMetric, classOf[InstanceUpdateActor], "ready-ops")

    /** the number of ops that we rejected because of a timeout */
    val timedOutOpsMeter: SettableGauge = Metrics.atomicGauge(ServiceMetric, classOf[InstanceUpdateActor], "ops-timeout")

    /** a timer around op processing */
    val processOpTimer: Timer = Metrics.timer(ServiceMetric, classOf[InstanceUpdateActor], "process-op")
  }
}

/**
  * This actor serializes [[InstanceOpProcessor.Operation]]s for the same instance. The operations
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
    processor: InstanceOpProcessor) extends Actor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  // this has to be a mutable field because we need to access it in postStop()
  private[impl] var operationsByInstanceId =
    Map.empty[Instance.Id, Queue[InstanceOpProcessor.Operation]].withDefaultValue(Queue.empty)

  override def preStart(): Unit = {
    metrics.numberOfActiveOps.setValue(0)
    metrics.numberOfQueuedOps.setValue(0)

    super.preStart()
  }

  override def postStop(): Unit = {
    super.postStop()

    // Answer all outstanding requests.
    operationsByInstanceId.values.foreach { queue =>
      queue.foreach { item =>
        item.sender ! Status.Failure(new IllegalStateException("InstanceUpdateActor stopped"))
      }
    }

    metrics.numberOfActiveOps.setValue(0)
    metrics.numberOfQueuedOps.setValue(0)
  }

  def receive: Receive = LoggingReceive {
    case ProcessInstanceOp(op @ InstanceOpProcessor.Operation(deadline, _, instanceId, _)) =>
      val oldQueue: Queue[InstanceOpProcessor.Operation] = operationsByInstanceId(instanceId)
      val newQueue = oldQueue :+ op
      operationsByInstanceId += instanceId -> newQueue
      metrics.numberOfQueuedOps.increment()

      if (oldQueue.isEmpty) {
        // start processing the just received operation
        processNextOpIfExists(instanceId)
      }

    case FinishedInstanceOp(op) =>
      val (dequeued, newQueue) = operationsByInstanceId(op.instanceId).dequeue
      require(dequeued == op)
      if (newQueue.isEmpty)
        operationsByInstanceId -= op.instanceId
      else
        operationsByInstanceId += op.instanceId -> newQueue

      val activeCount = metrics.numberOfActiveOps.decrement()
      if (log.isDebugEnabled) {
        val queuedCount = metrics.numberOfQueuedOps.value()
        log.debug(s"Finished processing ${op.op} for app [${op.appId}] and ${op.instanceId} "
          + s"$activeCount active, $queuedCount queued.")
      }

      processNextOpIfExists(op.instanceId)

    case Status.Failure(cause) =>
      // escalate this failure to our parent: InstanceTrackerActor
      throw new IllegalStateException("received failure", cause)
  }

  private[this] def processNextOpIfExists(instanceId: Instance.Id): Unit = {
    operationsByInstanceId(instanceId).headOption foreach { op =>
      val queuedCount = metrics.numberOfQueuedOps.decrement()
      val activeCount = metrics.numberOfActiveOps.increment()
      log.debug(s"Start processing ${op.op} for app [${op.appId}] and ${op.instanceId}. "
        + s"$activeCount active, $queuedCount queued.")

      import context.dispatcher
      val future = {
        if (op.deadline <= clock.now()) {
          metrics.timedOutOpsMeter.increment()
          op.sender ! Status.Failure(
            new TimeoutException(s"Timeout: ${op.op} for app [${op.appId}] and ${op.instanceId}.")
          )
          Future.successful(())
        } else
          metrics.processOpTimer(processor.process(op))
      }.map { _ =>
        log.debug(s"Finished processing ${op.op} for app [${op.appId}] and ${op.instanceId}")
        FinishedInstanceOp(op)
      }
      future.pipeTo(self)
    }
  }
}
