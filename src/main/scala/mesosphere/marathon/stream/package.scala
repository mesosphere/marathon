package mesosphere.marathon

import scala.language.implicitConversions
import scala.collection.TraversableLike
import scala.collection.convert.DecorateAsJava
package object stream {
  object Implicits extends StreamConversions with ScalaConversions with DecorateAsJava {
    implicit def toRichTraversableLike[A, Repr](t: TraversableLike[A, Repr]): RichTraversableLike[A, Repr] =
      new RichTraversableLike[A, Repr](t)
  }
}
