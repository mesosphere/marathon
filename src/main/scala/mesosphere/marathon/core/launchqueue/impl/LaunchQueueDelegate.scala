package mesosphere.marathon
package core.launchqueue.impl

import akka.Done
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.core.launchqueue.{LaunchQueue, LaunchQueueConfig}
import mesosphere.marathon.state.{PathId, RunSpec}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

private[launchqueue] class LaunchQueueDelegate(
    config: LaunchQueueConfig,
    launchQueueActor: ActorRef,
    rateLimiterActor: ActorRef) extends LaunchQueue with StrictLogging {

  // When purging, we wait for the TaskLauncherActor to shut down. This actor will wait for
  // in-flight task op notifications before complying, therefore we need to adjust the timeout accordingly.
  val purgeTimeout: Timeout = config.launchQueueRequestTimeout().milliseconds + config.taskOpNotificationTimeout().millisecond

  val launchQueueRequestTimeout: Timeout = config.launchQueueRequestTimeout().milliseconds

  override def listWithStatistics: Future[Seq[QueuedInstanceInfoWithStatistics]] =
    askQueueActorFuture[LaunchQueueDelegate.Request, Seq[QueuedInstanceInfoWithStatistics]]("listWithStatistics")(LaunchQueueDelegate.ListWithStatistics)

  override def notifyOfInstanceUpdate(update: InstanceChange): Future[Done] =
    askQueueActorFuture[InstanceChange, Done]("notifyOfInstanceUpdate")(update)

  override def purge(runSpecId: PathId): Future[Done] =
    askQueueActorFuture[LaunchQueueDelegate.Request, Done]("asyncPurge", timeout = purgeTimeout)(LaunchQueueDelegate.Purge(runSpecId))

  override def add(runSpec: RunSpec, count: Int): Future[Done] =
    askQueueActorFuture[LaunchQueueDelegate.Request, Done]("add")(LaunchQueueDelegate.Add(runSpec, count))

  private[this] def askQueueActorFuture[T, R: ClassTag](
    method: String,
    timeout: Timeout = launchQueueRequestTimeout)(message: T): Future[R] = {

    implicit val timeoutImplicit: Timeout = timeout
    val answerFuture = launchQueueActor ? message
    import scala.concurrent.ExecutionContext.Implicits.global
    answerFuture.recover {
      case NonFatal(e) => throw new RuntimeException(s"in $method", e)
    }
    answerFuture.mapTo[R]
  }

  override def addDelay(spec: RunSpec): Unit = rateLimiterActor ! RateLimiterActor.AddDelay(spec)

  override def resetDelay(spec: RunSpec): Unit = rateLimiterActor ! RateLimiterActor.ResetDelay(spec)

  override def advanceDelay(spec: RunSpec): Unit = rateLimiterActor ! RateLimiterActor.AdvanceDelay(spec)
}

private[impl] object LaunchQueueDelegate {
  sealed trait Request
  case object List extends Request
  case object ListWithStatistics extends Request
  case class Count(runSpecId: PathId) extends Request
  case class Purge(runSpecId: PathId) extends Request
  case class ConfirmPurge(runSpecId: PathId) extends Request
  case class Add(spec: RunSpec, count: Int) extends Request
}
