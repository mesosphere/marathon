package mesosphere.marathon.core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.launchqueue.{ LaunchQueue, LaunchQueueConfig }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ PathId, RunSpec }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag
import scala.util.control.NonFatal

private[launchqueue] class LaunchQueueDelegate(
    config: LaunchQueueConfig,
    actorRef: ActorRef,
    rateLimiterRef: ActorRef) extends LaunchQueue {

  override def list: Seq[QueuedTaskInfo] = {
    askQueueActor[LaunchQueueDelegate.Request, Seq[QueuedTaskInfo]]("list")(LaunchQueueDelegate.List)
  }

  override def get(runSpecId: PathId): Option[QueuedTaskInfo] =
    askQueueActor[LaunchQueueDelegate.Request, Option[QueuedTaskInfo]]("get")(LaunchQueueDelegate.Count(runSpecId))

  override def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedTaskInfo]] =
    askQueueActorFuture[TaskChanged, Option[QueuedTaskInfo]]("notifyOfTaskUpdate")(taskChanged).mapTo[Option[QueuedTaskInfo]]

  override def count(runSpecId: PathId): Int = get(runSpecId).map(_.tasksLeftToLaunch).getOrElse(0)

  override def listRunSpecs: Seq[RunSpec] = list.map(_.runSpec)

  override def purge(runSpecId: PathId): Unit = {
    // When purging, we wait for the TaskLauncherActor to shut down. This actor will wait for
    // in-flight task op notifications before complying, therefore we need to adjust the timeout accordingly.
    val purgeTimeout = config.launchQueueRequestTimeout().milliseconds + config.taskOpNotificationTimeout().millisecond
    askQueueActor[LaunchQueueDelegate.Request, Unit]("purge", timeout = purgeTimeout)(LaunchQueueDelegate.Purge(runSpecId))
  }

  override def add(runSpec: RunSpec, count: Int): Unit = askQueueActor[LaunchQueueDelegate.Request, Unit]("add")(LaunchQueueDelegate.Add(runSpec, count))

  private[this] def askQueueActor[T, R: ClassTag](
    method: String,
    timeout: FiniteDuration = config.launchQueueRequestTimeout().milliseconds)(message: T): R = {

    val answerFuture = askQueueActorFuture[T, R](method, timeout)(message)
    Await.result(answerFuture, timeout)
  }

  private[this] def askQueueActorFuture[T, R: ClassTag](
    method: String,
    timeout: FiniteDuration = config.launchQueueRequestTimeout().milliseconds)(message: T): Future[R] = {

    implicit val timeoutImplicit: Timeout = timeout
    val answerFuture = actorRef ? message
    import scala.concurrent.ExecutionContext.Implicits.global
    answerFuture.recover {
      case NonFatal(e) => throw new RuntimeException(s"in $method", e)
    }
    answerFuture.mapTo[R]
  }

  override def addDelay(runSpec: RunSpec): Unit = rateLimiterRef ! RateLimiterActor.AddDelay(runSpec)

  override def resetDelay(runSpec: RunSpec): Unit = rateLimiterRef ! RateLimiterActor.ResetDelay(runSpec)
}

private[impl] object LaunchQueueDelegate {
  sealed trait Request
  case object List extends Request
  case class Count(runSpecId: PathId) extends Request
  case class Purge(runSpecId: PathId) extends Request
  case object ConfirmPurge extends Request
  case class Add(runSpec: RunSpec, count: Int) extends Request
}
