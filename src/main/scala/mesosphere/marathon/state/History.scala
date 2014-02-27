package mesosphere.marathon.state

import scala.collection.SortedSet
import scala.math.{Ordered, Ordering}


/**
 * A sorted set of like-typed versioned elements.
 *
 * The natural ordering of this collection is derived from the orderedness
 * of the elements' versions.
 *
 * @tparam T the type of the elements of this collection
 * @tparam V the type of T's versions
 */
class History[T <: Versioned[V], V <: Ordered[V]] protected (
  underlying: SortedSet[T]
) extends SortedSet[T] {

  def this() =
    this(SortedSet[T]()(Versioned.versionOrdering[T, V]))

  def this(elems: Seq[T]) =
    this(SortedSet[T](elems: _*)(Versioned.versionOrdering[T, V]))

  def +(elem: T): History[T, V] = new History(underlying + elem)

  def -(elem: T): History[T, V] = new History(underlying - elem)

  def contains(elem: T): Boolean = underlying contains elem

  def iterator: Iterator[T] = underlying.iterator

  implicit def ordering: Ordering[T] = Versioned.versionOrdering[T, V]

  def rangeImpl(from: Option[T], until: Option[T]): History[T, V] =
    new History(underlying.rangeImpl(from, until))
}

object History {

  def apply[T <: Versioned[V], V <: Ordered[V]](): History[T, V] =
    new History[T, V]()

  def apply[T <: Versioned[V], V <: Ordered[V]](elems: T*): History[T, V] =
    new History[T, V](elems)
}
