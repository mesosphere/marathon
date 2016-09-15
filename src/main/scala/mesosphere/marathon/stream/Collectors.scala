package mesosphere.marathon.stream

import java.util
import java.util.function.{ BiConsumer, BinaryOperator, Function, Supplier }
import java.util.stream.Collector
import java.util.stream.Collector.Characteristics

import scala.collection.mutable
import scala.collection.immutable.Seq
import mesosphere.marathon.functional._

private class GenericCollector[T, C <: TraversableOnce[T]](builder: () => mutable.Builder[T, C])
    extends Collector[T, mutable.Builder[T, C], C] {
  override def supplier(): Supplier[mutable.Builder[T, C]] = builder

  override def combiner(): BinaryOperator[mutable.Builder[T, C]] =
    (buf1: mutable.Builder[T, C], buf2: mutable.Builder[T, C]) => buf1 ++= buf2.result

  override def finisher(): Function[mutable.Builder[T, C], C] = {
    (buf: mutable.Builder[T, C]) => buf.result()
  }

  override def accumulator(): BiConsumer[mutable.Builder[T, C], T] = { (buf: mutable.Builder[T, C], v: T) =>
    buf += v
  }

  override def characteristics(): util.Set[Characteristics] = util.Collections.emptySet()
}

private class MapCollector[K, V]()
    extends Collector[util.Map.Entry[K, V], mutable.Builder[(K, V), Map[K, V]], Map[K, V]] {

  override def supplier(): Supplier[mutable.Builder[(K, V), Map[K, V]]] = () => Map.newBuilder[K, V]

  override def combiner(): BinaryOperator[mutable.Builder[(K, V), Map[K, V]]] = {
    (buf1: mutable.Builder[(K, V), Map[K, V]], buf2: mutable.Builder[(K, V), Map[K, V]]) =>
      buf1 ++= buf2.result()
  }

  override def finisher(): Function[mutable.Builder[(K, V), Map[K, V]], Map[K, V]] = {
    (buf: mutable.Builder[(K, V), Map[K, V]]) => buf.result()
  }

  override def accumulator(): BiConsumer[mutable.Builder[(K, V), Map[K, V]], util.Map.Entry[K, V]] = {
    (buf: mutable.Builder[(K, V), Map[K, V]], v: util.Map.Entry[K, V]) =>
      buf += v.getKey -> v.getValue
  }

  override def characteristics(): util.Set[Characteristics] = util.Collections.emptySet()
}

/**
  * Collectors for java 8 streaming apis
  */
object Collectors {
  def seq[T]: Collector[T, mutable.Builder[T, Seq[T]], Seq[T]] = new GenericCollector(() => Seq.newBuilder[T])
  def indexedSeq[T]: Collector[T, mutable.Builder[T, IndexedSeq[T]], IndexedSeq[T]] =
    new GenericCollector(() => IndexedSeq.newBuilder[T])

  def map[K, V]: Collector[util.Map.Entry[K, V], mutable.Builder[(K, V), Map[K, V]], Map[K, V]] =
    new MapCollector[K, V]

  def set[T]: Collector[T, mutable.Builder[T, Set[T]], Set[T]] = new GenericCollector(() => Set.newBuilder[T])
}
