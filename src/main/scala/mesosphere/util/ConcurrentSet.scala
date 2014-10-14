package mesosphere.util

import scala.collection.concurrent.TrieMap
import scala.collection.generic.{ CanBuildFrom, GenericSetTemplate, MutableSetFactory }
import scala.collection.mutable

final class ConcurrentSet[A](elems: A*)
    extends mutable.Set[A]
    with GenericSetTemplate[A, ConcurrentSet]
    with mutable.SetLike[A, ConcurrentSet[A]]
    with mutable.FlatHashTable[A]
    with Serializable {
  import ConcurrentSet._

  private[this] val underlying = TrieMap[A, AnyRef](elems.map(_ -> Dummy): _*)

  override def +=(elem: A): this.type = {
    underlying.putIfAbsent(elem, Dummy)
    this
  }

  override def -=(elem: A): this.type = {
    underlying.remove(elem)
    this
  }

  override def contains(elem: A): Boolean = underlying.contains(elem)
  override def iterator: Iterator[A] = underlying.keysIterator
  override def companion: MutableSetFactory[ConcurrentSet] = ConcurrentSet
}

object ConcurrentSet extends MutableSetFactory[ConcurrentSet] {
  private[ConcurrentSet] val Dummy = new AnyRef

  override def apply[A](elems: A*): ConcurrentSet[A] = new ConcurrentSet[A](elems: _*)

  override def empty[A]: ConcurrentSet[A] = new ConcurrentSet[A]

  override def newBuilder[A]: mutable.Builder[A, ConcurrentSet[A]] =
    new mutable.SetBuilder[A, ConcurrentSet[A]](new ConcurrentSet[A]())

  implicit def canBuildFrom[A]: CanBuildFrom[Coll, A, ConcurrentSet[A]] =
    setCanBuildFrom[A]

  override def setCanBuildFrom[A]: CanBuildFrom[ConcurrentSet[_], A, ConcurrentSet[A]] =
    new CanBuildFrom[ConcurrentSet[_], A, ConcurrentSet[A]] {
      override def apply(from: ConcurrentSet[_]): mutable.Builder[A, ConcurrentSet[A]] = newBuilder[A]
      override def apply(): mutable.Builder[A, ConcurrentSet[A]] = newBuilder[A]
    }

}

