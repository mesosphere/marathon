package mesosphere.marathon.core

import scala.language.implicitConversions

/**
  * This package contains infrastructure code that is generally useful.
  */
package object base {
  implicit def toRichRuntime(runtime: Runtime): RichRuntime = new RichRuntime(runtime)
}
