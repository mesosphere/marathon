package mesosphere.marathon.state

trait Timestamped { def version: Timestamp }

object Timestamped {

  /**
    * Returns an ordering on type `T` derived from the natural ordering of
    * the `T`'s timestamps.
    */
  def timestampOrdering[T <: Timestamped](): Ordering[T] = Ordering.by(_.version)
}
