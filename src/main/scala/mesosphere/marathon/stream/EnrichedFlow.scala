package mesosphere.marathon
package stream

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }

object EnrichedFlow {
  @SuppressWarnings(Array("AsInstanceOf"))
  def ignore[T]: Flow[T, Nothing, NotUsed] =
    Flow[T].filter(_ => false).asInstanceOf[Flow[T, Nothing, NotUsed]]

  /**
    * Stops the current stream the moment an element is produced by the following source
    */
  def stopOnFirst[T](s: Source[Any, Any]): Flow[T, T, NotUsed] = {
    Flow[T].merge(s.take(1).via(ignore), eagerComplete = true)
  }

  def dedup[T](filterInitial: T = null): Flow[T, T, NotUsed] = {
    Flow[T].statefulMapConcat { () =>
      var lastElement: T = filterInitial

      { e =>
        if (lastElement == e)
          Nil
        else {
          lastElement = e
          Seq(e)
        }
      }
    }
  }
}
