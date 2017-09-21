package mesosphere.marathon
package util

import scala.collection.generic.CanBuild

object RichEither {
  // Remove for Scala 2.12
  implicit class RightBiased[A, B](val e: Either[A, B]) extends AnyVal {
    def foreach[U](f: B => U): Unit = e.right.foreach(f)
    def map[C](f: B => C): Either[A, C] = e.right.map(f)
    def flatMap[C](f: B => Either[A, C]) = e.right.flatMap(f)
  }

  import scala.language.higherKinds
  /**
    * If any lefts are in the sequence, then returns a sequence of all lefts
    * Otherwise, returns sequence of all rights
    */
  def sequence[S[T] <: Iterable[T], L, R](eithers: S[Either[L, R]])(
    implicit
    leftCbf: CanBuild[L, S[L]], rightCbf: CanBuild[R, S[R]]): Either[S[L], S[R]] = {
    var leftDetected = false
    val lefts = leftCbf.apply()
    val rights = rightCbf.apply()
    eithers.foreach {
      case Left(l) =>
        leftDetected = true
        lefts += l
      case Right(r) if !leftDetected =>
        rights += r
      case _ =>
        ()
    }
    if (leftDetected)
      Left(lefts.result)
    else
      Right(rights.result)
  }
}
