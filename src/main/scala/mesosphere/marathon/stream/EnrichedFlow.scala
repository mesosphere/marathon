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

  /**
    * Drops elements when two subseqent elements are the same. Behaves similar to Unix uniq and does not keep track of
    * all values seen from the beginning of the stream.
    *
    *     Source(List(1,2,2,1,1,3,3)).dedup() becomes Source(List(1,2,1,3))
    *
    *     Source(List(None,Some(1),None)).dedup(filterInitial = None) becomes Source(List(Some(1), None))
    *
    * @param filterInitial If the first value is this, then drop it
    */
  @SuppressWarnings(Array("NullAssignment"))
  def dedup[T](initialFilterElement: T = null): Flow[T, T, NotUsed] = {
    Flow[T].statefulMapConcat { () =>
      var lastElement: T = initialFilterElement

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
