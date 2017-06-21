package mesosphere.marathon
package stream

import java.util

import mesosphere.marathon.functional._

import scala.collection.immutable.{ IndexedSeq, Seq, Set }
import scala.collection.mutable
import scala.language.implicitConversions

/**
  * Conversions that make java collections appear like scala collections, generally using the stream
  * api to do so (its generally faster than scala's built-ins)
  */
trait ScalaConversions extends StreamConversions {
  implicit class RichArray[T](array: Array[T]) extends mutable.IndexedSeq[T] {
    override def update(idx: Int, elem: T): Unit = array(idx) = elem
    override def length: Int = array.length
    override def apply(idx: Int): T = array(idx)
  }

  implicit class RichIterator[T](iterator: util.Iterator[T]) extends Iterator[T] {
    override def foreach[U](f: (T) => U): Unit = iterator.forEachRemaining(f)
    override def hasNext: Boolean = iterator.hasNext
    override def next(): T = iterator.next()
  }

  implicit class RichCollection[T](collection: util.Collection[T]) extends Iterable[T] {
    override def foreach[U](f: (T) => U): Unit = collection.stream().foreach(f)
    override def toSeq: Seq[T] = ScalaConversions.toSeq(collection)
    override def toIndexedSeq: IndexedSeq[T] = ScalaConversions.toIndexedSeq(collection)
    override def toSet[B >: T]: Set[B] = ScalaConversions.toSet(collection)
    override def iterator: Iterator[T] = collection.iterator()
  }

  implicit class RichSet[T](set: util.Set[T]) extends Set[T] {
    override def contains(elem: T): Boolean = set.contains(elem)
    override def +(elem: T): Set[T] = set.toSet + elem // linter:ignore TypeToType
    override def -(elem: T): Set[T] = set.toSet - elem // linter:ignore TypeToType
    override def iterator: Iterator[T] = set.iterator()
  }

  implicit class RichMap[K, V](map: util.Map[K, V]) extends Traversable[(K, V)] {
    override def foreach[U](f: ((K, V)) => U): Unit =
      map.entrySet().stream().foreach(entry => f(entry.getKey -> entry.getValue))
    def toMap: Map[K, V] = ScalaConversions.toMap(map)
  }

  def toSeq[T](collection: util.Collection[T]): Seq[T] =
    collection.stream().collect(Collectors.seq[T])

  def toIndexedSeq[T](collection: util.Collection[T]): IndexedSeq[T] =
    collection.stream().collect(Collectors.indexedSeq[T])

  def toSet[T, B >: T](collection: util.Collection[T]): Set[B] =
    collection.stream().collect(Collectors.set[B])

  def toMap[K, V](map: util.Map[K, V]): Map[K, V] =
    map.entrySet().stream().collect(Collectors.map[K, V])

  implicit def toTraversableOnce[T](enum: util.Enumeration[T]): RichEnumeration[T] = new RichEnumeration[T](enum)
}

object ScalaConversions extends ScalaConversions
