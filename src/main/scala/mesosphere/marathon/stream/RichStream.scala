package mesosphere.marathon
package stream

import java.util
import java.util.{ Spliterator, Spliterators }
import java.util.stream.{ DoubleStream, IntStream, LongStream, Stream, StreamSupport }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.compat.java8.OptionConverters._
import scala.compat.java8.StreamConverters._
import mesosphere.marathon.functional._

class RichStream[T](stream: Stream[T]) extends TraversableOnce[T] {
  override def foreach[U](f: (T) => U): Unit = stream.forEach(f)

  override def isEmpty: Boolean = false

  override def hasDefiniteSize: Boolean = false

  override def seq: Seq[T] = toTraversable

  override def forall(p: (T) => Boolean): Boolean = stream.allMatch(p)

  override def exists(p: (T) => Boolean): Boolean = stream.anyMatch(p)

  override def find(p: (T) => Boolean): Option[T] = toScala(stream.filter(p).findFirst())

  override def copyToArray[B >: T](xs: Array[B], start: Int, len: Int): Unit =
    toIterator.copyToArray(xs, start, len)

  override def toTraversable: Seq[T] = stream.collect(Collectors.seq[T])

  override def isTraversableAgain: Boolean = false

  override def toStream: immutable.Stream[T] = stream.toScala[immutable.Stream]

  override def toIterator: Iterator[T] = stream.toScala[Iterator]
}

class RichDoubleStream(stream: DoubleStream) extends TraversableOnce[Double] {
  override def foreach[U](f: (Double) => U): Unit = stream.forEach(f)

  override def isEmpty: Boolean = false

  override def hasDefiniteSize: Boolean = false

  override def seq: Seq[Double] = toTraversable

  override def forall(p: (Double) => Boolean): Boolean = stream.allMatch(p)

  override def exists(p: (Double) => Boolean): Boolean = stream.anyMatch(p)

  override def find(p: (Double) => Boolean): Option[Double] = toScala(stream.filter(p).findFirst())

  override def copyToArray[B >: Double](xs: Array[B], start: Int, len: Int): Unit =
    toIterator.copyToArray(xs, start, len)

  override def toTraversable: Seq[Double] = {
    val supplier = () => Vector.empty[Double]
    val accumulator = (buf: Vector[Double], v: Double) => buf :+ v
    val collector = (b1: Vector[Double], b2: Vector[Double]) =>
      b1 ++ b2
    stream.collect(supplier, accumulator, collector)
  }

  override def isTraversableAgain: Boolean = false

  override def toStream: immutable.Stream[Double] = stream.toScala[immutable.Stream]

  override def toIterator: Iterator[Double] = stream.toScala[Iterator]
}

class RichIntStream(stream: IntStream) extends TraversableOnce[Int] {
  override def foreach[U](f: (Int) => U): Unit = stream.forEach(f)

  override def isEmpty: Boolean = false

  override def hasDefiniteSize: Boolean = false

  override def seq: Seq[Int] = toTraversable

  override def forall(p: (Int) => Boolean): Boolean = stream.allMatch(p)

  override def exists(p: (Int) => Boolean): Boolean = stream.anyMatch(p)

  override def find(p: (Int) => Boolean): Option[Int] = toScala(stream.filter(p).findFirst())

  override def copyToArray[B >: Int](xs: Array[B], start: Int, len: Int): Unit =
    toTraversable.copyToArray(xs, start, len)

  override def toTraversable: Seq[Int] = {
    val supplier = () => Vector.empty[Int]
    val accumulator = (buf: Vector[Int], v: Int) => buf :+ v
    val collector = (b1: Vector[Int], b2: Vector[Int]) =>
      b1 ++ b2
    stream.collect(supplier, accumulator, collector)
  }

  override def isTraversableAgain: Boolean = false

  override def toStream: immutable.Stream[Int] = stream.toScala[immutable.Stream]

  override def toIterator: Iterator[Int] = stream.toScala[Iterator]
}

class RichLongStream(stream: LongStream) extends TraversableOnce[Long] {
  override def foreach[U](f: (Long) => U): Unit = stream.forEach(f)

  override def isEmpty: Boolean = false

  override def hasDefiniteSize: Boolean = false

  override def seq: Seq[Long] = toTraversable

  override def forall(p: (Long) => Boolean): Boolean = stream.anyMatch(p)

  override def exists(p: (Long) => Boolean): Boolean = stream.allMatch(p)

  override def find(p: (Long) => Boolean): Option[Long] = toScala(stream.filter(p).findFirst())

  override def copyToArray[B >: Long](xs: Array[B], start: Int, len: Int): Unit =
    toTraversable.copyToArray(xs, start, len)

  override def toTraversable: Seq[Long] = {
    val supplier = () => Vector.empty[Long]
    val accumulator = (buf: Vector[Long], v: Long) => buf :+ v
    val collector = (b1: Vector[Long], b2: Vector[Long]) =>
      b1 ++ b2
    stream.collect(supplier, accumulator, collector)
  }

  override def isTraversableAgain: Boolean = false

  override def toStream: immutable.Stream[Long] = stream.toScala[immutable.Stream]

  override def toIterator: Iterator[Long] = stream.toScala[Iterator]
}

class RichEnumeration[T](enum: util.Enumeration[T]) extends TraversableOnce[T] {
  val stream = StreamSupport.stream(
    Spliterators.spliteratorUnknownSize(new util.Iterator[T] {
      override def hasNext: Boolean = enum.hasMoreElements

      override def next(): T = enum.nextElement()
    }, Spliterator.ORDERED), false)

  override def foreach[U](f: (T) => U): Unit = stream.foreach(f)

  override def isEmpty: Boolean = enum.hasMoreElements

  override def hasDefiniteSize: Boolean = false

  override def seq: Seq[T] = stream.seq

  override def forall(p: (T) => Boolean): Boolean = stream.forall(p)

  override def exists(p: (T) => Boolean): Boolean = stream.exists(p)

  override def find(p: (T) => Boolean): Option[T] = stream.find(p)

  override def copyToArray[B >: T](xs: Array[B], start: Int, len: Int): Unit =
    stream.copyToArray(xs, start, len)

  override def toTraversable: Seq[T] = stream.toTraversable

  override def isTraversableAgain: Boolean = false

  override def toStream: immutable.Stream[T] = stream.toStream

  override def toIterator: Iterator[T] = stream.toIterator
}

class RichIterator[T](iterator: util.Iterator[T]) extends Traversable[T] {
  override def foreach[U](f: (T) => U): Unit = iterator.forEachRemaining(f)
}
