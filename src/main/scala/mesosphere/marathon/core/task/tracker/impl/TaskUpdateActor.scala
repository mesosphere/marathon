package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, Props, Status }
import akka.event.LoggingReceive
import mesosphere.marathon.core.task.tracker.impl.TaskUpdateActor.{ ActorMetrics, FinishedTaskOp, ProcessTaskOp }
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Queue

object TaskUpdateActor {
  def props(metrics: ActorMetrics, processor: TaskOpProcessor): Props = {
    Props(new TaskUpdateActor(metrics, processor))
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

    /** the number of queued ops that are not currently processed */
    val numberOfQueuedOps = metrics.gauge(name("number-of-queued-ops"), new AtomicIntGauge)

    /** the number of currently processed ops */
    val numberOfActiveOps = metrics.gauge(name("number-of-active-ops"), new AtomicIntGauge)

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
private[impl] class TaskUpdateActor(metrics: ActorMetrics, processor: TaskOpProcessor) extends Actor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  // this has to be a mutable field because we need to access it in postStop()
  private[impl] var operationsByTaskId =
    Map.empty[String, Queue[TaskOpProcessor.Operation]].withDefaultValue(Queue.empty)

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
    case ProcessTaskOp(op @ TaskOpProcessor.Operation(_, _, taskId, _)) =>
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
        log.debug(s"Finished processing ${op.action} for app [${op.appId}] and task [${op.taskId}] "
          + s"$activeCount active, $queuedCount queued.");
      }

      processNextOpIfExists(op.taskId)

    case Status.Failure(cause) =>
      // escalate this failure to our parent: TaskTrackerActor
      throw new IllegalStateException("received failure", cause)
  }

  private[this] def processNextOpIfExists(taskId: String): Unit = {
    operationsByTaskId(taskId).headOption foreach { op =>
      val queuedCount = metrics.numberOfQueuedOps.decrement()
      val activeCount = metrics.numberOfActiveOps.increment()
      log.debug(s"Start processing ${op.action} for app [${op.appId}] and task [${op.taskId}]. "
        + s"$activeCount active, $queuedCount queued.")

      import context.dispatcher
      val processOpFuture =
        metrics.processOpTimer.timeFuture {
          val future = processor.process(op)
          future.map { _ =>
            log.debug(s"Finished processing ${op.action} for app [${op.appId}] and task [${op.taskId}]")
            FinishedTaskOp(op)
          }
        }

      import akka.pattern.pipe
      processOpFuture.pipeTo(self)
    }
  }
}
