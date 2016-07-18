package mesosphere.util

import javax.annotation.PreDestroy

import akka.actor._
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.util.RestrictParallelExecutionsActor.Finished
import org.slf4j.LoggerFactory

import scala.collection.immutable.Queue
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Try }

/**
  * Allows capping parallel executions of methods which return `scala.concurrent.Future`s.
  * Only `maxConcurrent` concurrent executions are allowed.
  *
  * The methods will be executed on the provided executionContext.
  *
  * {{{
  * scala> import mesosphere.util.CapConcurrentExecutions
  * scala> val capParallelExecution = CapConcurrentExecutions(someActorRefFactory, "serialize")
  * scala> def myFutureReturningFunc: Future[Int] = Future.successful(1)
  * scala> val result: Future[Int] = capParallelExecution(myFutureReturningFunc)
  * }}}
  */
object CapConcurrentExecutions {
  private val log = LoggerFactory.getLogger(getClass.getName)

  def apply[T](
    metrics: CapConcurrentExecutionsMetrics,
    actorRefFactory: ActorRefFactory,
    actorName: String,
    maxConcurrent: Int,
    maxQueued: Int)(implicit ctx: ExecutionContext = ExecutionContext.global): CapConcurrentExecutions = {
    new CapConcurrentExecutions(metrics, actorRefFactory, actorName, maxConcurrent, maxQueued)
  }
}

class CapConcurrentExecutionsMetrics(metrics: Metrics, metricsClass: Class[_]) {
  val queued = metrics.gauge(metrics.name(MetricPrefixes.SERVICE, metricsClass, "queued"), new AtomicIntGauge)
  val processing = metrics.gauge(metrics.name(MetricPrefixes.SERVICE, metricsClass, "processing"), new AtomicIntGauge)
  val processingTimer = metrics.timer(metrics.name(MetricPrefixes.SERVICE, metricsClass, "processing-time"))

  def reset(): Unit = {
    queued.setValue(0)
    processing.setValue(0)
  }
}

object CapConcurrentExecutionsMetrics {
  def apply[T](metrics: Metrics, metricsClass: Class[_]): CapConcurrentExecutionsMetrics =
    new CapConcurrentExecutionsMetrics(metrics, metricsClass)
}

class CapConcurrentExecutions private (
    metrics: CapConcurrentExecutionsMetrics,
    actorRefFactory: ActorRefFactory,
    actorName: String,
    maxParallel: Int,
    maxQueued: Int)(implicit ctx: ExecutionContext) {
  import CapConcurrentExecutions.log

  private[util] val serializeExecutionActorRef = {
    val serializeExecutionActorProps =
      RestrictParallelExecutionsActor.props(metrics, maxParallel = maxParallel, maxQueued = maxQueued, ctx = ctx)
    actorRefFactory.actorOf(serializeExecutionActorProps, actorName)
  }

  /**
    * Submit the given block to execution.
    */
  def apply[T](block: => Future[T]): Future[T] = {
    val promise = Promise[T]()
    serializeExecutionActorRef ! RestrictParallelExecutionsActor.Execute(promise, () => block)
    promise.future
  }

  @PreDestroy
  def close(): Unit = {
    log.debug(s"stopping $serializeExecutionActorRef")
    serializeExecutionActorRef ! PoisonPill
  }
}

/**
  * Accepts execute instructions containing functions returning `scala.concurrent.Future`s.
  * It only allows `maxConcurrent` executions and queues the other operations.
  * It will not queue more than `maxQueued` execute instructions.
  */
private[util] class RestrictParallelExecutionsActor(
    metrics: CapConcurrentExecutionsMetrics,
    maxConcurrent: Int,
    maxQueued: Int,
    ctx: ExecutionContext) extends Actor {

  import RestrictParallelExecutionsActor.Execute

  private[this] var active: Int = 0
  private[this] var queue: Queue[Execute[_]] = Queue.empty

  override def preStart(): Unit = {
    super.preStart()

    metrics.reset()

  }

  override def postStop(): Unit = {
    metrics.reset()

    for (execute <- queue) {
      execute.complete(Failure(new IllegalStateException(s"$self actor stopped")))
    }

    queue = Queue.empty

    super.postStop()
  }

  override def receive: Receive = {
    case exec: Execute[_] =>
      if (active >= maxConcurrent && queue.size >= maxQueued) {
        sender ! Status.Failure(new IllegalStateException(s"$self queue may not exceed $maxQueued entries"))
      } else {
        queue :+= exec
        startNextIfPossible()
      }

    case Finished =>
      active -= 1
      startNextIfPossible()
  }

  private[this] def startNextIfPossible(): Unit = {
    if (active < maxConcurrent) {
      startNext()
    }

    metrics.processing.setValue(active)
    metrics.queued.setValue(queue.size)
  }

  private[this] def startNext(): Unit = {
    queue.dequeueOption.foreach {
      case (next, newQueue) =>
        queue = newQueue
        active += 1
        val myself = self

        ctx.execute(new Runnable() {
          override def run(): Unit = {
            val future: Future[_] =
              try {
                metrics.processingTimer.timeFuture(next.func())
              } catch {
                // Intentionally move all thrown exceptions into the future,
                // it is not the responsibility of this method to handle
                // filter throwables but rather to move them into the future.
                case e: Throwable => Future.failed(e)
              }

            future.onComplete { (result: Try[_]) =>
              next.complete(result)
              myself ! Finished
            }(CallerThreadExecutionContext.callerThreadExecutionContext)
          }
        })

    }
  }
}

private[util] object RestrictParallelExecutionsActor {
  def props(metrics: CapConcurrentExecutionsMetrics, maxParallel: Int, maxQueued: Int, ctx: ExecutionContext): Props =
    Props(new RestrictParallelExecutionsActor(metrics, maxConcurrent = maxParallel, maxQueued = maxQueued, ctx = ctx))

  private val log = LoggerFactory.getLogger(getClass.getName)
  case class Execute[T](promise: Promise[T], func: () => Future[T]) {
    def complete(result: Try[_]): Unit = promise.complete(result.asInstanceOf[Try[T]])
  }
  private case object Finished
}
