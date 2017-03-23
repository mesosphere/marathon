package mesosphere.marathon
package stream

import akka.actor.{ ActorRef, Props, Status }
import akka.{ Done, NotUsed }
import akka.stream.{ Graph, SinkShape, UniformFanOutShape }
import akka.stream.scaladsl.{ SinkQueueWithCancel, Sink => AkkaSink }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
  * Extensions to Akka's Sink companion
  */
object Sink {
  def set[T]: AkkaSink[T, Future[immutable.Set[T]]] = {
    AkkaSink.fromGraph(new CollectionStage[T, immutable.Set[T]](immutable.Set.newBuilder[T]))
  }

  def sortedSet[T](implicit ordering: Ordering[T]): AkkaSink[T, Future[immutable.SortedSet[T]]] = {
    AkkaSink.fromGraph(new CollectionStage[T, immutable.SortedSet[T]](immutable.SortedSet.newBuilder[T]))
  }

  def map[K, V]: AkkaSink[(K, V), Future[immutable.Map[K, V]]] = {
    AkkaSink.fromGraph(new CollectionStage[(K, V), immutable.Map[K, V]](immutable.Map.newBuilder[K, V]))
  }

  def list[T]: AkkaSink[T, Future[List[T]]] = {
    AkkaSink.fromGraph(new CollectionStage[T, List[T]](List.newBuilder[T]))
  }

  // Akka's API
  def fromGraph[T, M](g: Graph[SinkShape[T], M]): AkkaSink[T, M] = AkkaSink.fromGraph(g)
  def fromSubscriber[T](subscriber: Subscriber[T]): AkkaSink[T, NotUsed] = AkkaSink.fromSubscriber(subscriber)
  def cancelled[T]: AkkaSink[T, NotUsed] = AkkaSink.cancelled
  def head[T]: AkkaSink[T, Future[T]] = AkkaSink.head
  def headOption[T]: AkkaSink[T, Future[Option[T]]] = AkkaSink.headOption
  def last[T]: AkkaSink[T, Future[T]] = AkkaSink.last[T]
  def lastOption[T]: AkkaSink[T, Future[Option[T]]] = AkkaSink.lastOption[T]
  def seq[T]: AkkaSink[T, Future[Seq[T]]] = AkkaSink.seq[T]
  def asPublisher[T](fanout: Boolean): AkkaSink[T, Publisher[T]] = AkkaSink.asPublisher[T](fanout)
  def ignore: AkkaSink[Any, Future[Done]] = AkkaSink.ignore
  def foreach[T](f: T => Unit): AkkaSink[T, Future[Done]] = AkkaSink.foreach[T](f)
  def combine[T, U](
    first: AkkaSink[U, _],
    second: AkkaSink[U, _],
    rest: AkkaSink[U, _]*)(strategy: Int ⇒ Graph[UniformFanOutShape[T, U], NotUsed]): AkkaSink[T, NotUsed] =
    AkkaSink.combine[T, U](first, second, rest: _*)(strategy)
  def foreachParallel[T](parallelism: Int)(f: T ⇒ Unit)(implicit ec: ExecutionContext): AkkaSink[T, Future[Done]] =
    AkkaSink.foreachParallel[T](parallelism)(f)
  def fold[U, T](zero: U)(f: (U, T) ⇒ U): AkkaSink[T, Future[U]] = AkkaSink.fold[U, T](zero)(f)
  def reduce[T](f: (T, T) ⇒ T): AkkaSink[T, Future[T]] = AkkaSink.reduce(f)
  def onComplete[T](callback: Try[Done] => Unit): AkkaSink[T, NotUsed] = AkkaSink.onComplete(callback)
  def actorRef[T](ref: ActorRef, onCompleteMessage: Any): AkkaSink[T, NotUsed] =
    AkkaSink.actorRef(ref, onCompleteMessage)
  def actorRefWithAck[T](ref: ActorRef, onInitMessage: Any, ackMessage: Any, onCompleteMessage: Any,
    onFailureMessage: (Throwable) ⇒ Any = Status.Failure): AkkaSink[T, NotUsed] =
    AkkaSink.actorRefWithAck(ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage)
  def actorSubscriber[T](props: Props): AkkaSink[T, ActorRef] = AkkaSink.actorSubscriber(props)
  def queue[T](): AkkaSink[T, SinkQueueWithCancel[T]] = AkkaSink.queue[T]()
}
