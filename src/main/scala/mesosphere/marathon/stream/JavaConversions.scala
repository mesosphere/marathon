package mesosphere.marathon
package stream

import scala.language.implicitConversions

import java.util

import scala.collection.convert.DecorateAsJava
import scala.collection.immutable.Seq

trait JavaConversions extends DecorateAsJava {

  implicit class RichCollection[T](collection: util.Collection[T]) extends Traversable[T] {
    override def foreach[U](f: (T) => U): Unit = collection.stream().foreach(f)
    override def toSeq: Seq[T] = JavaConversions.toSeq(collection)
  }

  implicit class RichMap[K, V](map: util.Map[K, V]) extends Traversable[(K, V)] {
    override def foreach[U](f: ((K, V)) => U): Unit =
      map.entrySet().stream().foreach(entry => f(entry.getKey -> entry.getValue))
    def toMap: Map[K, V] = JavaConversions.toMap(map)
  }

  def toSeq[T](collection: util.Collection[T]): Seq[T] =
    collection.stream().collect(Collectors.seq[T])

  def toIndexedSeq[T](collection: util.Collection[T]): IndexedSeq[T] =
    collection.stream().collect(Collectors.indexedSeq[T])

  def toSet[T](collection: util.Collection[T]): Set[T] =
    collection.stream().collect(Collectors.set[T])

  def toMap[K, V](map: util.Map[K, V]): Map[K, V] =
    map.entrySet().stream().collect(Collectors.map[K, V])

  implicit def toTraversableOnce[T](enum: util.Enumeration[T]): RichEnumeration[T] = new RichEnumeration[T](enum)
  implicit def toTraversable[T](it: util.Iterator[T]): RichIterator[T] = new RichIterator[T](it)
}

object JavaConversions extends JavaConversions