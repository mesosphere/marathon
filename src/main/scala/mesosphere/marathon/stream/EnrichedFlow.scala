package mesosphere.marathon
package stream

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }

@SuppressWarnings(Array("AsInstanceOf"))
object EnrichedFlow {
  /**
    * Drops all elements and has an output type of Nothing.
    */
  val ignore: Flow[Any, Nothing, NotUsed] =
    Flow[Any].filter(_ => false).asInstanceOf[Flow[Any, Nothing, NotUsed]]

  /**
    * Stops the current stream the moment an element is produced by specified source, without merging any elements from
    * the specified source into the current stream
    *
    * In the following example, 4 TimeToPoll elements would be emitted:
    *
    *    Source.tick(0.mills, 1000.millis, TimeToPoll).via(stopOnFirst(Source(3500.millis, 3500.millis, 'stop)))
    *
    * @param source The Source which, upon producing its first element, causes the current stream to stop
    */
  def stopOnFirst[T](source: Source[Any, Any]): Flow[T, T, NotUsed] = {
    Flow[T].merge(source.take(1).via(ignore), eagerComplete = true)
  }
}
