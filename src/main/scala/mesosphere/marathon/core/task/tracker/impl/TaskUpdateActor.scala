package mesosphere.marathon.core.task.tracker.impl

import java.util.concurrent.TimeoutException

import akka.actor.{ Actor, Props, Status }
import akka.event.LoggingReceive
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskUpdateActor.{ ActorMetrics, FinishedTaskOp, ProcessTaskOp }
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Queue
import scala.concurrent.Future

object TaskUpdateActor {
  def props(clock: Clock, metrics: ActorMetrics, processor: TaskOpProcessor): Props = {
    Props(new TaskUpdateActor(clock, metrics, processor))
  }

  /** Request that the [[TaskUpdateActor]] should process the given op. */
  private[impl] case class ProcessTaskOp(op: TaskOpProcessor.Operation)
  /**
    * Internal message of the [[TaskUpdateActor]] which indicates that an operation has been processed completely.
    * It might have succeeded or failed.
    */
  private case class FinishedTaskOp(op: TaskOpProcessor.Operation)

  class ActorMetrics(metrics: Metrics) {
    private[this] def name(name: String): String =
      metrics.name(MetricPrefixes.SERVICE, classOf[TaskUpdateActor], name)

    /** the number of ops that are for tasks that already have an op ready */
    val numberOfQueuedOps = metrics.gauge(name("delayed-ops"), new AtomicIntGauge)

    /** the number of currently processed ops */
    val numberOfActiveOps = metrics.gauge(name("ready-ops"), new AtomicIntGauge)

    /** the number of ops that we rejected because of a timeout */
    val timedOutOpsMeter = metrics.meter(name("ops-timeout"))

    /** a timer around op processing */
    val processOpTimer = metrics.timer(name("process-op"))
  }
}

/**
  * This actor serializes [[TaskOpProcessor.Operation]]s for the same task. The operations
  * are executed by the given processor.
  *
  * Assumptions:
  * * This actor must be the only one performing task updates.
  * * This actor is spawned as a child of the [[TaskTrackerActor]].
  * * Errors in this actor lead to a restart of the TaskTrackerActor.
  */
private[impl] class TaskUpdateActor(
    clock: Clock,
    metrics: ActorMetrics,
    processor: TaskOpProcessor) extends Actor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  // this has to be a mutable field because we need to access it in postStop()
  private[impl] var operationsByTaskId =
    Map.empty[Task.Id, Queue[TaskOpProcessor.Operation]].withDefaultValue(Queue.empty)

  override def preStart(): Unit = {
    metrics.numberOfActiveOps.setValue(0)
    metrics.numberOfQueuedOps.setValue(0)

    super.preStart()
  }

  override def postStop(): Unit = {
    super.postStop()

    // Answer all outstanding requests.
    operationsByTaskId.values.iterator.flatten.map(_.sender) foreach { sender =>
      sender ! Status.Failure(new IllegalStateException("TaskUpdateActor stopped"))
    }

    metrics.numberOfActiveOps.setValue(0)
    metrics.numberOfQueuedOps.setValue(0)
  }

  def receive: Receive = LoggingReceive {
    case ProcessTaskOp(op @ TaskOpProcessor.Operation(deadline, _, taskId, _)) =>
      val oldQueue: Queue[TaskOpProcessor.Operation] = operationsByTaskId(taskId)
      val newQueue = oldQueue :+ op
      operationsByTaskId += taskId -> newQueue
      metrics.numberOfQueuedOps.increment()

      if (oldQueue.isEmpty) {
        // start processing the just received operation
        processNextOpIfExists(taskId)
      }

    case FinishedTaskOp(op) =>
      val (dequeued, newQueue) = operationsByTaskId(op.taskId).dequeue
      require(dequeued == op)
      if (newQueue.isEmpty)
        operationsByTaskId -= op.taskId
      else
        operationsByTaskId += op.taskId -> newQueue

      val activeCount = metrics.numberOfActiveOps.decrement()
      if (log.isDebugEnabled) {
        val queuedCount = metrics.numberOfQueuedOps.getValue
        log.debug(s"Finished processing ${op.stateOp} for app [${op.appId}] and ${op.taskId} "
          + s"$activeCount active, $queuedCount queued.");
      }

      processNextOpIfExists(op.taskId)

    case Status.Failure(cause) =>
      // escalate this failure to our parent: TaskTrackerActor
      throw new IllegalStateException("received failure", cause)
  }

  private[this] def processNextOpIfExists(taskId: Task.Id): Unit = {
    operationsByTaskId(taskId).headOption foreach { op =>
      val queuedCount = metrics.numberOfQueuedOps.decrement()
      val activeCount = metrics.numberOfActiveOps.increment()
      log.debug(s"Start processing ${op.stateOp} for app [${op.appId}] and ${op.taskId}. "
        + s"$activeCount active, $queuedCount queued.")

      import context.dispatcher
      val future = {
        if (op.deadline <= clock.now()) {
          metrics.timedOutOpsMeter.mark()
          op.sender ! Status.Failure(
            new TimeoutException(s"Timeout: ${op.stateOp} for app [${op.appId}] and ${op.taskId}.")
          )
          Future.successful(())
        }
        else
          metrics.processOpTimer.timeFuture(processor.process(op))
      }.map { _ =>
        log.debug(s"Finished processing ${op.stateOp} for app [${op.appId}] and ${op.taskId}")
        FinishedTaskOp(op)
      }

      import akka.pattern.pipe
      future.pipeTo(self)
    }
  }
}
