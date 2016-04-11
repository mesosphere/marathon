package mesosphere.marathon.core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.launchqueue.{ LaunchQueue, LaunchQueueConfig }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ AppDefinition, PathId }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal

private[launchqueue] class LaunchQueueDelegate(
    config: LaunchQueueConfig,
    actorRef: ActorRef,
    rateLimiterRef: ActorRef) extends LaunchQueue {

  override def list: Seq[QueuedTaskInfo] = {
    askQueueActor("list")(LaunchQueueDelegate.List)
      .asInstanceOf[Seq[QueuedTaskInfo]]
  }

  override def get(appId: PathId): Option[QueuedTaskInfo] =
    askQueueActor("get")(LaunchQueueDelegate.Count(appId)).asInstanceOf[Option[QueuedTaskInfo]]

  override def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedTaskInfo]] =
    askQueueActorFuture("notifyOfTaskUpdate")(taskChanged).mapTo[Option[QueuedTaskInfo]]

  override def count(appId: PathId): Int = get(appId).map(_.tasksLeftToLaunch).getOrElse(0)

  override def listApps: Seq[AppDefinition] = list.map(_.app)

  override def purge(appId: PathId): Unit = {
    // When purging, we wait for the AppTaskLauncherActor to shut down. This actor will wait for
    // in-flight task op notifications before complying, therefore we need to adjust the timeout accordingly.
    val purgeTimeout = config.launchQueueRequestTimeout().milliseconds + config.taskOpNotificationTimeout().millisecond
    askQueueActor("purge", timeout = purgeTimeout)(LaunchQueueDelegate.Purge(appId))
  }

  override def add(app: AppDefinition, count: Int): Unit = askQueueActor("add")(LaunchQueueDelegate.Add(app, count))

  private[this] def askQueueActor[T](
    method: String,
    timeout: FiniteDuration = config.launchQueueRequestTimeout().milliseconds)(message: T): Any = {

    val answerFuture: Future[Any] = askQueueActorFuture(method, timeout)(message)
    Await.result(answerFuture, timeout)
  }

  private[this] def askQueueActorFuture[T](
    method: String,
    timeout: FiniteDuration = config.launchQueueRequestTimeout().milliseconds)(message: T): Future[Any] = {

    implicit val timeoutImplicit: Timeout = timeout
    val answerFuture = actorRef ? message
    import scala.concurrent.ExecutionContext.Implicits.global
    answerFuture.recover {
      case NonFatal(e) => throw new RuntimeException(s"in $method", e)
    }
    answerFuture
  }

  override def addDelay(app: AppDefinition): Unit = rateLimiterRef ! RateLimiterActor.AddDelay(app)

  override def resetDelay(app: AppDefinition): Unit = rateLimiterRef ! RateLimiterActor.ResetDelay(app)
}

private[impl] object LaunchQueueDelegate {
  sealed trait Request
  case object List extends Request
  case class Count(appId: PathId) extends Request
  case class Purge(appId: PathId) extends Request
  case object ConfirmPurge extends Request
  case class Add(app: AppDefinition, count: Int) extends Request
}
