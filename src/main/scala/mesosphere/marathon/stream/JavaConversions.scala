package mesosphere.marathon
package stream

import java.util
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Map.Entry

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

/**
  * Very small and tight/fast wrappers around scala collections that integrate natively with java.
  */
trait JavaConversions {
  implicit class JavaIterable[T](sc: Iterable[T]) extends util.AbstractCollection[T] {
    override def size(): Int = sc.size
    override def iterator(): util.Iterator[T] = sc.toIterator.asJava
  }

  implicit class JavaImmutableList[T](sc: Seq[T]) extends util.AbstractList[T] {
    override def get(i: Int): T = sc(i)
    override def size(): Int = sc.size
  }

  implicit class JavaSet[T](sc: Set[T]) extends util.AbstractSet[T] {
    override def size(): Int = sc.size
    override def iterator(): util.Iterator[T] = sc.toIterator.asJava
  }

  implicit class JavaMap[K, V](m: Map[K, V]) extends util.AbstractMap[K, V] {
    lazy val entries: Set[Entry[K, V]] =
      m.map { case (k, v) => new SimpleImmutableEntry[K, V](k, v) }(collection.breakOut)
    override def entrySet(): util.Set[Entry[K, V]] = entries
  }
}

object JavaConversions extends JavaConversions
