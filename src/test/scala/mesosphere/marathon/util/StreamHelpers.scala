package mesosphere.marathon
package util

import akka.NotUsed
import akka.stream.scaladsl.Source

object StreamHelpers {

  /**
    * Source which conforms to any type; never completes and never emits anything.
    */
  val sourceNever: Source[Nothing, NotUsed] = Source.maybe[Nothing].mapMaterializedValue { _ => NotUsed }
}
