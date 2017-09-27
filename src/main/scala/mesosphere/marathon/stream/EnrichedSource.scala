package mesosphere.marathon
package stream

import akka.actor.{ Cancellable, PoisonPill }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import mesosphere.marathon.util.CancellableOnce

object EnrichedSource {

  /**
    * Stream that produces no elements, but is cancellable
    */
  val emptyCancellable: Source[Nothing, Cancellable] =
    Source.queue[Nothing](1, OverflowStrategy.fail).mapMaterializedValue { m =>
      new CancellableOnce(() => m.complete())
    }

  /**
    * Returns a Source which subscribes to messages of the given type
    *
    * (copied from akka.stream.scaladsl.Source$.actorRef)
    *
    * Depending on the defined akka.stream.OverflowStrategy it might drop elements if there is no space available in the
    * buffer.
    *
    * The strategy akka.stream.OverflowStrategy.backpressure is not supported, and an IllegalArgument("Backpressure
    * overflowStrategy not supported") will be thrown if it is passed as argument.
    *
    * The buffer can be disabled by using bufferSize of 0 and then received messages are dropped if there is no demand
    * from downstream. When bufferSize is 0 the overflowStrategy does not matter. An async boundary is added after this
    * Source; as such, it is never safe to assume the downstream will always generate demand.
    *
    * @param message runtime type of messages. *DO NOT* specify parameterized types here. (classOf[List[_]] instead of classOf[List[String]])
    * @param eventStream
    * @param bufferSize The number of elements to buffer when back-pressured downstream
    * @param overflowStrategy Strategy that is  used when incoming elements cannot fit inside the buffer
    */
  def eventBusSource[T](
    message: Class[T],
    eventStream: akka.event.EventStream,
    bufferSize: Int,
    overflowStrategy: OverflowStrategy): Source[T, Cancellable] = {
    val source = Source.actorRef[T](bufferSize, overflowStrategy)

    source.
      mapMaterializedValue { ref =>
        eventStream.subscribe(ref, message)
        new CancellableOnce(() => ref ! PoisonPill)
      }
  }
}
