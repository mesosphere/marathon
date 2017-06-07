package mesosphere.marathon
package stream

import scala.collection.TraversableLike
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag

/**
  * Extends traversable with a few helper methods.
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

  private def total[U, T](pf: PartialFunction[U, T], otherwise: T): U => T = {
    case t if pf.isDefinedAt(t) => pf(t)
    case _ => otherwise
  }

  /**
    * Works like `exists` but allows to define a partial predicate function.
    */
  def existsPF(pf: PartialFunction[A, Boolean]): Boolean = to.exists(total(pf, otherwise = false))

  /**
    * Works like `find` but allows to define a partial predicate function.
    */
  def findPF(pf: PartialFunction[A, Boolean]): Option[A] = to.find(total(pf, otherwise = false))

  /**
    * Works like `filter` but allows to define a partial predicate function.
    */
  def filterPF(pf: PartialFunction[A, Boolean]): Repr = to.filter(total(pf, otherwise = false))

  /**
    * Works like `exists` but searches for an element of a given type.
    */
  def existsAn[E](implicit tag: ClassTag[E]): Boolean = to.exists(tag.runtimeClass.isInstance)
}
