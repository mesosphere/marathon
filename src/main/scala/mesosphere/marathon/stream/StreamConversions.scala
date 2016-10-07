package mesosphere.marathon
package stream

import scala.language.implicitConversions

import java.util.stream._

trait StreamConversions {
  implicit def toScalaTraversableOnce[T](stream: Stream[T]): RichStream[T] = new RichStream(stream)
  implicit def toScalaTraversableOnce(stream: DoubleStream): RichDoubleStream = new RichDoubleStream(stream)
  implicit def toScalaTraversableOnce(stream: IntStream): RichIntStream = new RichIntStream(stream)
  implicit def toScalaTraversableOnce(stream: LongStream): RichLongStream = new RichLongStream(stream)
}
