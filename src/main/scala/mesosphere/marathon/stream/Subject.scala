package mesosphere.marathon
package stream

import akka.Done
import akka.actor.Cancellable
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink => AkkaSink, Source, SourceQueueWithComplete}
import akka.stream.stage.{GraphStageLogic, InHandler, GraphStageWithMaterializedValue}
import akka.stream.{Attributes, Inlet, SinkShape}
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.util.CancellableOnce
import scala.util.{Failure, Success, Try}

/**
  * Internal, stream-indepent logic used for managing the subject. The Subject sink needs to continue to function
  * after the originating stream terminates. This logic is contained in a vanilla class to prevent usage of any akka
  * stream methods that would stop working once said stream terminates.
  */
private class SubjectInternalLogic[T](queueSize: Int, overflowStrategy: OverflowStrategy) {
  @volatile private[this] var subscribers: Set[SourceQueueWithComplete[T]] = Set.empty
  @volatile private[this] var lastElement: T = _
  @volatile private[this] var elementPushed = false
  @volatile private[this] var completionResult: Option[Try[Done]] = None

  def onElement(t: T): Unit = synchronized {
    elementPushed = true
    lastElement = t
    subscribers.foreach(_.offer(t))
  }

  private def addSubscriber(sq: SourceQueueWithComplete[T]): Unit = synchronized {
    completionResult match {
      case Some(r) =>
        if (r.isSuccess && elementPushed)
          sq.offer(lastElement)

        propagateCompletion(sq, r)

      case None =>
        if (elementPushed) sq.offer(lastElement)
        subscribers += sq
    }
  }

  private[this] def propagateCompletion(sq: SourceQueueWithComplete[T], result: Try[Done]) =
    result match {
      case Success(_) => sq.complete()
      case Failure(ex) => sq.fail(ex)
    }

  private def removeSubscriber(sq: SourceQueueWithComplete[T]): Unit = synchronized {
    subscribers -= sq
  }

  def complete(result: Try[Done]): Unit = synchronized {
    completionResult = Some(result)
    subscribers.foreach(propagateCompletion(_, result))
    subscribers = Set.empty
  }

  val newSubscriberSource = Source.queue[T](queueSize, overflowStrategy).mapMaterializedValue { sq =>
    addSubscriber(sq)
    sq.watchCompletion().onComplete { _ => removeSubscriber(sq) }(ExecutionContexts.callerThread)
    new CancellableOnce(() => sq.complete())
  }
}

/**
  * See Subject.apply for docs
  */
class Subject[T](queueSize: Int, overflowStrategy: OverflowStrategy)
  extends GraphStageWithMaterializedValue[SinkShape[T], Source[T, Cancellable]] {
  val input = Inlet[T]("Subject.in")

  override def shape: SinkShape[T] = new SinkShape(input)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Source[T, Cancellable]) = {
    class SubjectGraphStageLogic(internalLogic: SubjectInternalLogic[T]) extends GraphStageLogic(shape) {
      require(overflowStrategy != OverflowStrategy.backpressure, "backpressure not supported")

      override def preStart(): Unit =
        pull(input)

      setHandler(input, new InHandler {
        override def onPush(): Unit = {
          internalLogic.onElement(grab(input))
          pull(input)
        }

        override def onUpstreamFinish(): Unit = internalLogic.complete(Success(Done))
        override def onUpstreamFailure(ex: Throwable): Unit = internalLogic.complete(Failure(ex))
      })
    }

    val internalLogic = new SubjectInternalLogic[T](queueSize, overflowStrategy)
    (new SubjectGraphStageLogic(internalLogic), internalLogic.newSubscriberSource)
  }
}

object Subject {
  /**
    * Sink which works similar to BroadcastHub, but does not back-pressure and will emit the last element recieved to
    * any new subscribers. If there are no subscribers, then it behaves similar to Sink.ignore, except it continually
    * remembers the last element received.
    *
    * All messages are relayed asynchronously, the individual consumption rates of each subscriber do not affect
    * others. Backpressure overflow mechanism not supported.
    *
    * Upstream completion or failures are propagated to the subscribers (even to late subscribers)
    *
    * In the event of stream failure, subscriber streams may or may not receive the last element before receiving the
    * error. All bets are off.
    */
  def apply[T](queueSize: Int, overflowStrategy: OverflowStrategy): AkkaSink[T, Source[T, Cancellable]] = {
    require(overflowStrategy != OverflowStrategy.backpressure, "backpressure overflow strategy not supported")
    AkkaSink.fromGraph(new Subject[T](queueSize, overflowStrategy))
  }
}
