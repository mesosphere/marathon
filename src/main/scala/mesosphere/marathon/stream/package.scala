package mesosphere.marathon

import scala.language.implicitConversions
package object stream {
  object Implicits {
    implicit def toRichIterable[A, Repr](t: Iterable[A]): RichIterable[A] =
      new RichIterable[A](t)
  }
}
