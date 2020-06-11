package mesosphere.marathon
package stream

import scala.reflect.ClassTag

/**
  * Extends traversable with a few helper methods.
  */
class RichIterable[+A](to: Iterable[A]) {
  /**
    * Works like `exists` but searches for an element of a given type.
    */
  def existsAn[E](implicit tag: ClassTag[E]): Boolean = to.exists(tag.runtimeClass.isInstance)
}
