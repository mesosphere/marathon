package mesosphere.marathon
package stream

import akka.stream.scaladsl.Sink

import scala.collection.immutable
import scala.concurrent.Future

/**
  * Extensions to Akka's Sink companion
  */
object EnrichedSink {
  def set[T]: Sink[T, Future[immutable.Set[T]]] = {
    Sink.fromGraph(new CollectionStage[T, immutable.Set[T]](immutable.Set.newBuilder[T]))
  }

  def sortedSet[T](implicit ordering: Ordering[T]): Sink[T, Future[immutable.SortedSet[T]]] = {
    Sink.fromGraph(new CollectionStage[T, immutable.SortedSet[T]](immutable.SortedSet.newBuilder[T]))
  }

  def map[K, V]: Sink[(K, V), Future[immutable.Map[K, V]]] = {
    Sink.fromGraph(new CollectionStage[(K, V), immutable.Map[K, V]](immutable.Map.newBuilder[K, V]))
  }

  def list[T]: Sink[T, Future[List[T]]] = {
    Sink.fromGraph(new CollectionStage[T, List[T]](List.newBuilder[T]))
  }
}
