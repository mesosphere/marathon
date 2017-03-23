package mesosphere.marathon

import java.util.concurrent.locks.ReentrantLock

import com.typesafe.config.Config

import scala.concurrent.Future
import scala.language.implicitConversions

package object util {
  type Success[T] = scala.util.Success[T]
  val Success = scala.util.Success
  type Failure[T] = scala.util.Failure[T]
  val Failure = scala.util.Failure

  implicit def toRichFuture[T](f: Future[T]): RichFuture[T] = new RichFuture(f)
  implicit def toRichLock[T](l: ReentrantLock): RichLock = new RichLock(l)
  implicit def toRichConfig[T](c: Config): RichConfig = new RichConfig(c)
}
