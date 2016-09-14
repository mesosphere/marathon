package mesosphere.marathon.stream

import java.util
import java.util.function.{ BiConsumer, BinaryOperator, Function, Supplier }
import java.util.stream.Collector
import java.util.stream.Collector.Characteristics

import scala.collection.mutable
import scala.collection.immutable.Seq
import scala.compat.java8.FunctionConverters._

private class GenericCollector[T, C <: TraversableOnce[T]](builder: () => mutable.Builder[T, C])
    extends Collector[T, mutable.Builder[T, C], C] {
  override def supplier(): Supplier[mutable.Builder[T, C]] = {
    () => builder()
  }.asJava

  override def combiner(): BinaryOperator[mutable.Builder[T, C]] = {
    (buf1: mutable.Builder[T, C], buf2: mutable.Builder[T, C]) =>
      buf1 ++= buf2.result()
  }.asJava

  override def finisher(): Function[mutable.Builder[T, C], C] = {
    (buf: mutable.Builder[T, C]) => buf.result()
  }.asJava

  override def accumulator(): BiConsumer[mutable.Builder[T, C], T] = {
    (buf: mutable.Builder[T, C], v: T) =>
      buf += v
      ()
  }.asJava

  override def characteristics(): util.Set[Characteristics] = util.Collections.emptySet()
}

/**
  * Collectors for java 8 streaming apis
  */
object Collectors {
  def seq[T]: Collector[T, mutable.Builder[T, Seq[T]], Seq[T]] = new GenericCollector(() => Seq.newBuilder[T])
  def map[K, V]: Collector[(K, V), mutable.Builder[(K, V), Map[K, V]], Map[K, V]] =
    new GenericCollector(() => Map.newBuilder[K, V])
  def set[T]: Collector[T, mutable.Builder[T, Set[T]], Set[T]] = new GenericCollector(() => Set.newBuilder[T])
}
