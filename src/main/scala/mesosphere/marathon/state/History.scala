package mesosphere.marathon.state

import scala.collection.SortedSet
import scala.math.{Ordered, Ordering}


/**
 * A sorted set of like-typed timestamped elements.
 *
 * The natural ordering of this collection is derived from the orderedness
 * of the elements' timestamps.
 *
 * @tparam T the type of the elements of this collection
 */
class History[T <: Timestamped] protected (
  underlying: SortedSet[T]
) extends SortedSet[T] {

  def this() =
    this(SortedSet[T]()(Timestamped.timestampOrdering[T]))

  def this(elems: Seq[T]) =
    this(SortedSet[T](elems: _*)(Timestamped.timestampOrdering[T]))

  def +(elem: T): History[T] = new History(underlying + elem)

  def -(elem: T): History[T] = new History(underlying - elem)

  def contains(elem: T): Boolean = underlying contains elem

  def iterator: Iterator[T] = underlying.iterator

  implicit def ordering: Ordering[T] = Timestamped.timestampOrdering[T]

  def rangeImpl(from: Option[T], until: Option[T]): History[T] =
    new History(underlying.rangeImpl(from, until))
}

object History {

  def apply[T <: Timestamped](): History[T] =
    new History[T]()

  def apply[T <: Timestamped](elems: T*): History[T] =
    new History[T](elems)
}
