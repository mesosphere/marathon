package mesosphere.marathon
package util

import mesosphere.marathon.core.async.ExecutionContexts

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try

class RichFuture[T](val future: Future[T]) extends AnyVal {
  /**
    * Change this Future from T to Try[T] (never failing).
    * This is particularly useful for async/await
    * @return A new Future that doesn't fail
    */
  def asTry: Future[Try[T]] = {
    val promise = Promise[Try[T]]()
    future.onComplete {
      x: Try[T] => promise.success(x)
    }(ExecutionContexts.callerThread)
    promise.future
  }

  def mapAll[U](pf: PartialFunction[Try[T], U])(implicit ec: ExecutionContext): Future[U] = {
    val p = Promise[U]()
    future.onComplete(r => p.complete(Try(pf(r))))
    p.future
  }
}
