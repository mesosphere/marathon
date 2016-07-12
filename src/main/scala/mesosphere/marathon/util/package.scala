package mesosphere.marathon

import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.Future
import scala.language.implicitConversions

package object util {
  implicit def toRichFuture[T](f: Future[T]): RichFuture[T] = new RichFuture(f)
  implicit def toRichLock[T](l: ReentrantLock): RichLock = new RichLock(l)
}
