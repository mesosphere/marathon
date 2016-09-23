package mesosphere.marathon
package stream

import scala.collection.TraversableLike
import scala.collection.generic.CanBuildFrom

/**
  * Extends traversable with a filter that isn't stupid
  */
class RichTraversableLike[+A, +Repr](to: TraversableLike[A, Repr]) {
  private def filterAsImpl[B >: A, That](p: A => Boolean, isFlipped: Boolean)(implicit cbf: CanBuildFrom[Repr, B, That]): That = {
    val b = cbf(to.repr)
    to.foreach { x =>
      if (p(x) != isFlipped) b += x
    }
    b.result
  }

  def filterAs[B >: A, That](p: A => Boolean)(implicit cbf: CanBuildFrom[Repr, B, That]): That =
    filterAsImpl(p, isFlipped = false)

  def filterNotAs[B >: A, That](p: A => Boolean)(implicit cbf: CanBuildFrom[Repr, B, That]): That =
    filterAsImpl(p, isFlipped = true)
}
