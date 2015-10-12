package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ Cancellable, Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.impl.RateLimiterActor.{
  CleanupOverdueDelays,
  AddDelay,
  DecreaseDelay,
  DelayUpdate,
  GetDelay,
  ResetDelay,
  ResetDelayResponse
}
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusObservables }
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import org.apache.mesos.Protos.TaskID
import rx.lang.scala.Subscription
import scala.concurrent.duration._

import scala.concurrent.Future
import scala.util.control.NonFatal

private[launchqueue] object RateLimiterActor {
  def props(
    rateLimiter: RateLimiter,
    taskTracker: TaskTracker,
    appRepository: AppRepository,
    launchQueueRef: ActorRef): Props =
    Props(new RateLimiterActor(
      rateLimiter, taskTracker, appRepository, launchQueueRef
    ))

  case class DelayUpdate(app: AppDefinition, delayUntil: Timestamp)

  case class ResetDelay(app: AppDefinition)
  case object ResetDelayResponse

  case class GetDelay(appDefinition: AppDefinition)
  private[impl] case class AddDelay(app: AppDefinition)
  private[impl] case class DecreaseDelay(app: AppDefinition)

  private case object CleanupOverdueDelays
}

private class RateLimiterActor private (
    rateLimiter: RateLimiter,
    taskTracker: TaskTracker,
    appRepository: AppRepository,
    launchQueueRef: ActorRef) extends Actor with ActorLogging {
  var cleanup: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    cleanup = context.system.scheduler.schedule(10.seconds, 10.seconds, self, CleanupOverdueDelays)
    log.info("started RateLimiterActor")
  }

  override def postStop(): Unit = {
    cleanup.cancel()
  }

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveCleanup,
      receiveDelayOps
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def receiveCleanup: Receive = {
    case CleanupOverdueDelays =>
      // If an app gets removed or updated, the delay should be reset.
      // Still, we can remove overdue delays before that and also make leaks less likely
      // by calling this periodically.
      rateLimiter.cleanUpOverdueDelays()
  }

  private[this] def receiveDelayOps: Receive = {
    case GetDelay(app) =>
      sender() ! DelayUpdate(app, rateLimiter.getDelay(app))

    case AddDelay(app) =>
      rateLimiter.addDelay(app)
      launchQueueRef ! DelayUpdate(app, rateLimiter.getDelay(app))

    case DecreaseDelay(app) => // ignore for now

    case ResetDelay(app) =>
      rateLimiter.resetDelay(app)
      launchQueueRef ! DelayUpdate(app, rateLimiter.getDelay(app))
      sender() ! ResetDelayResponse
  }
}
