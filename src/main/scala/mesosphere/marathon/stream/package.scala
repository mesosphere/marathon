package mesosphere.marathon

import scala.language.implicitConversions
import scala.collection.TraversableLike

package object stream {
  object Implicits extends StreamConversions with ScalaConversions with JavaConversions {
    implicit def toRichTraversableLike[A, Repr](t: TraversableLike[A, Repr]): RichTraversableLike[A, Repr] =
      new RichTraversableLike[A, Repr](t)
  }
}
