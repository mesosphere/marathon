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
    launchQueueRef: ActorRef,
    taskStatusObservables: TaskStatusObservables): Props =
    Props(new RateLimiterActor(
      rateLimiter, taskTracker, appRepository, launchQueueRef, taskStatusObservables
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
    launchQueueRef: ActorRef,
    taskStatusObservables: TaskStatusObservables) extends Actor with ActorLogging {
  var taskStatusSubscription: Subscription = _
  var cleanup: Cancellable = _

  override def preStart(): Unit = {
    taskStatusSubscription = taskStatusObservables.forAll.subscribe(self ! _)
    import context.dispatcher
    cleanup = context.system.scheduler.schedule(10.seconds, 10.seconds, self, CleanupOverdueDelays)
    log.info("started RateLimiterActor")
  }

  override def postStop(): Unit = {
    taskStatusSubscription.unsubscribe()
    cleanup.cancel()
  }

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveCleanup,
      receiveDelayOps,
      receiveTaskStatusUpdate
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

  private[this] def receiveTaskStatusUpdate: Receive = {
    case TaskStatusUpdate(_, taskId, status) =>
      status match {
        case MarathonTaskStatus.Terminal(terminal) if !terminal.killed =>
          sendToSelfForApp(taskId, AddDelay(_))

        case MarathonTaskStatus.Running(mesosStatus) if mesosStatus.forall(s => !s.hasHealthy || s.getHealthy) =>
          // FIXME: Decrease delay is ignored for now
          // Also we probably want to somehow consider the Marathon health status (HTTP/TCP health checks)
          sendToSelfForApp(taskId, DecreaseDelay(_))

        case _ => // Ignore
      }
  }

  private[this] def sendToSelfForApp(taskId: TaskID, toMessage: AppDefinition => Any): Unit = {
    val appId = TaskIdUtil.appId(taskId)
    val maybeTask: Option[MarathonTask] = taskTracker.fetchTask(appId, taskId.getValue)
    val maybeAppFuture: Future[Option[AppDefinition]] = maybeTask match {
      case Some(task) =>
        val version: Timestamp = Timestamp(task.getVersion)
        val appFuture = appRepository.app(appId, version)
        import context.dispatcher
        appFuture.foreach {
          case None => log.info("App '{}' with version '{}' not found. Outdated?", appId, version)
          case _    =>
        }
        appFuture.recover {
          case NonFatal(e) =>
            log.error("error while retrieving app '{}', version '{}'", appId, version, e)
            None
        }
      case None =>
        log.warning("Received update for unknown task '{}'", taskId.getValue)
        Future.successful(None)
    }

    import context.dispatcher
    maybeAppFuture.foreach {
      case Some(app) => self ! toMessage(app)
      case None      => // nothing
    }
  }
}
