package mesosphere

import mesosphere.marathon.state.Timestamp

/**
  * Scala stupidly defines Seq/Indexed as "a generic sequence" which can be _mutable_
  *
  * Instead, provided you use
  * ```
  * package mesosphere.marathon
  * package subpackage
  * ```
  * the correct Seq type till be imported for you automatically.
  */
package object marathon {
  type Seq[+A] = scala.collection.immutable.Seq[A]
  val Seq = scala.collection.immutable.Seq

  type IndexedSeq[+A] = scala.collection.immutable.IndexedSeq[A]
  val IndexedSeq = scala.collection.immutable.IndexedSeq

  implicit class RichClock(val c: java.time.Clock) extends AnyVal {
    // This method was formerly implemented on the marathon Clock type to return Marathon's Timestamp type
    // We preserve it for now to reduce the size of the change to remove Marathon's Clock type.
    def now(): Timestamp = Timestamp.now(c)
  }

  object NonEmpty {
    def unapply[I <: Iterable[_]](iter: I): Boolean = iter.nonEmpty
  }

  object NonEmptyIterable {

    def apply[A](head: A, tail: Iterable[A] = Iterable.empty[A]): NonEmptyIterable[A] = new NonEmptyIterable(head, tail)

    def unapply[A, I <: Iterable[A]](iter: I with Iterable[A]): Option[NonEmptyIterable[A]] = {
      if (iter.nonEmpty) Some(NonEmptyIterable(iter.head, iter.tail))
      else None
    }
  }

  class NonEmptyIterable[A](head: A, tail: Iterable[A]) extends Iterable[A] {
    override def iterator: Iterator[A] = Iterator.apply(head) ++ tail.iterator

    override def headOption: Option[A] = throw new NoSuchMethodException("Please use NonEmptyIterable.head instead.")
  }

  /**
    * This makes the silent compiler annotation available in our mesosphere.marathon prelude, and is used to suppress
    * compiler warnings.
    */
  type silent = com.github.ghik.silencer.silent
}
