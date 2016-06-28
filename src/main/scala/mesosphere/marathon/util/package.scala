package mesosphere.marathon

import scala.concurrent.Future
import scala.language.implicitConversions

package object util {
  implicit def toRichFuture[T](f: Future[T]): RichFuture[T] = new RichFuture(f)
}
