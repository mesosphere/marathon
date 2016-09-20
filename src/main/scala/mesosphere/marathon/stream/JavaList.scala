package mesosphere.marathon.stream

import java.util

import scala.collection.immutable.Seq

class JavaList[T](sc: Seq[T]) extends util.AbstractList[T] {
  override def get(i: Int): T = sc(i)
  override def size(): Int = sc.size
}
