package mesosphere.marathon
package stream

import akka.actor.Cancellable
import akka.stream.scaladsl.{ Sink => AkkaSink }
import akka.Done
import akka.stream.scaladsl.{ Source, SourceQueueWithComplete }
import akka.stream.OverflowStrategy
import mesosphere.marathon.util.CancellableOnce
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

object Repeater {
  private class RepeaterLogic[T](queueSize: Int, overflowStrategy: OverflowStrategy)(implicit ec: ExecutionContext) {
    require(overflowStrategy != OverflowStrategy.backpressure, "backpressure not supported")
    @volatile private var subscribers: Set[SourceQueueWithComplete[T]] = Set.empty
    @volatile private var lastElement: T = _
    @volatile private var elementPushed = false
    @volatile private var completionResult: Option[Try[Done]] = None

    val onElement: T => Unit = { t =>
      synchronized {
        elementPushed = true
        lastElement = t
        subscribers.foreach(_.offer(t))
      }
    }

    val newSubscriberSource = Source.queue[T](queueSize, overflowStrategy).mapMaterializedValue { sq =>
      synchronized {
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
      sq.watchCompletion().onComplete { _ =>
        synchronized {
          subscribers -= sq
        }
      }

      new CancellableOnce(() => sq.complete())
    }

    private def propagateCompletion(sq: SourceQueueWithComplete[T], result: Try[Done]) =
      result match {
        case Success(_) => sq.complete()
        case Failure(ex) => sq.fail(ex)
      }

    def complete(result: Try[Done]): Unit = synchronized {
      completionResult = Some(result)
      subscribers.foreach(propagateCompletion(_, result))
      subscribers = Set.empty
    }
  }

  /**
    * Sink which works similar to BroadcastHub, but does not back-pressure and will emit the last element recieved to
    * any new subscribers. If there are no subscribers, then it behaves similar to Sink.ignore, except it continually
    * remembers the last element received.
    *
    * All messages are relayed asynchronously, the individual consumption rates of each subscriber do not affect
    * others. Backpressure mechanism not supported.
    *
    * Upstream completion or failures are propagated to the subscribers (even to late subscribers)
    *
    * In the event of stream failure, subscriber streams may or may not receive the last element before receiving the
    * error. All bets are off.
    */
  def sink[T](queueSize: Int, overflowStrategy: OverflowStrategy)(implicit ec: ExecutionContext): AkkaSink[T, Source[T, Cancellable]] = {
    AkkaSink.fromGraph(new ForEachMatSink[T, Source[T, Cancellable]]({ streamCompleted =>
      val logic = new RepeaterLogic[T](queueSize, overflowStrategy)
      streamCompleted.onComplete { case result => logic.complete(result) }
      (logic.onElement, logic.newSubscriberSource)
    }))
  }
}
