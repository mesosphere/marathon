package mesosphere.marathon.state

import scala.math.{Ordered, Ordering}


/**
 * A mixin for versioned data types.
 */
trait Versioned[V <: Ordered[V]] { val version: V }

object Versioned {

  /**
   * Returns an ordering on type `T` derived from the natural ordering of
   * `T`'s version type.
   */
  def versionOrdering[T <: Versioned[V], V <: Ordered[V]](): Ordering[T] =
    Ordering.by { (item: T) => item.version }
}