package mesosphere.marathon

object NonEmptyIterable {

  def apply[A](head: A, tail: Iterable[A] = Iterable.empty[A]): NonEmptyIterable[A] = new NonEmptyIterable(head, tail)

  def unapply[A, I <: Iterable[A]](iter: I with Iterable[A]): Option[NonEmptyIterable[A]] = {
    if (iter.nonEmpty) Some(NonEmptyIterable(iter.head, iter.tail))
    else None
  }
}

case class NonEmptyIterable[A](override val head: A, override val tail: Iterable[A]) extends Iterable[A] {
  override def iterator: Iterator[A] = Iterator.apply(head) ++ tail.iterator

  override def headOption: Option[A] = Some(head)
}
