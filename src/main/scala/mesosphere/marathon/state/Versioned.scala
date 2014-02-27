package mesosphere.marathon.state

import scala.math.{ Ordered, Ordering }

/**
 * A mixin for versioned data types.
 */
trait Versioned[V <: Ordered[V]] {
  val version: V
}
