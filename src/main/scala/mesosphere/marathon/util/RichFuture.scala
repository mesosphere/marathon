package mesosphere.marathon.util

import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.{ Future, Promise }
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
      case x: Try[T] => promise.success(x)
    }(CallerThreadExecutionContext.callerThreadExecutionContext)
    promise.future
  }
}
