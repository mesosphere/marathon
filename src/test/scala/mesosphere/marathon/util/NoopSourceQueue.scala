package mesosphere.marathon
package util

import akka.Done
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete
import scala.concurrent.Future

class NoopSourceQueue[T] extends SourceQueueWithComplete[T] {
  override def offer(elem: T): Future[QueueOfferResult] =
    Future.successful(QueueOfferResult.Enqueued)

  override def watchCompletion(): Future[Done] =
    Future.successful(Done)

  override def complete(): Unit = ()

  override def fail(ex: Throwable): Unit = ()
}

object NoopSourceQueue {
  def apply[T]() =
    new NoopSourceQueue[T]
}
