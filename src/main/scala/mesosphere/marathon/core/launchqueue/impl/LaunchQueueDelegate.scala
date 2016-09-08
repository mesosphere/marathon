package mesosphere.marathon.core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.{ LaunchQueue, LaunchQueueConfig }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ PathId, RunSpec }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal

private[launchqueue] class LaunchQueueDelegate(
    config: LaunchQueueConfig,
    actorRef: ActorRef,
    rateLimiterRef: ActorRef) extends LaunchQueue {

  override def list: Seq[QueuedInstanceInfo] = {
    askQueueActor("list")(LaunchQueueDelegate.List)
      .asInstanceOf[Seq[QueuedInstanceInfo]]
  }

  override def get(runSpecId: PathId): Option[QueuedInstanceInfo] =
    askQueueActor("get")(LaunchQueueDelegate.Count(runSpecId)).asInstanceOf[Option[QueuedInstanceInfo]]

  override def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedInstanceInfo]] =
    askQueueActorFuture("notifyOfTaskUpdate")(taskChanged).mapTo[Option[QueuedInstanceInfo]]

  override def count(runSpecId: PathId): Int = get(runSpecId).map(_.instancesLeftToLaunch).getOrElse(0)

  override def listRunSpecs: Seq[RunSpec] = list.map(_.runSpec)

  override def purge(runSpecId: PathId): Unit = {
    // When purging, we wait for the TaskLauncherActor to shut down. This actor will wait for
    // in-flight task op notifications before complying, therefore we need to adjust the timeout accordingly.
    val purgeTimeout = config.launchQueueRequestTimeout().milliseconds + config.taskOpNotificationTimeout().millisecond
    askQueueActor("purge", timeout = purgeTimeout)(LaunchQueueDelegate.Purge(runSpecId))
  }

  override def add(spec: RunSpec, count: Int): Unit = askQueueActor("add")(LaunchQueueDelegate.Add(spec, count))

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

  override def addDelay(spec: RunSpec): Unit = rateLimiterRef ! RateLimiterActor.AddDelay(spec)

  override def resetDelay(spec: RunSpec): Unit = rateLimiterRef ! RateLimiterActor.ResetDelay(spec)
}

private[impl] object LaunchQueueDelegate {
  sealed trait Request
  case object List extends Request
  case class Count(runSpecId: PathId) extends Request
  case class Purge(runSpecId: PathId) extends Request
  case object ConfirmPurge extends Request
  case class Add(spec: RunSpec, count: Int) extends Request
}
