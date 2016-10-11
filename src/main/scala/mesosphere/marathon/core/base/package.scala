package mesosphere.marathon.core

import scala.language.implicitConversions

package object base {
  implicit def toRichRuntime(runtime: Runtime): RichRuntime = new RichRuntime(runtime)
}
