package mesosphere.marathon
package core

import org.slf4j.MDC
import java.util

package object async {
  /** public only to enable our akka dispatch weaving to use it */
  def propagateContext[T](context: Map[Context.ContextName[_], Any], mdc: Option[util.Map[String, String]])(f: => T): T = {
    val oldMdc = Option(MDC.getCopyOfContextMap)
    val oldContext = Context.copy // linter:ignore
    try {
      mdc.fold(MDC.clear())(MDC.setContextMap)
      Context.set(context)
      f
    } finally {
      oldMdc.fold(MDC.clear())(MDC.setContextMap)
      Context.set(oldContext)
    }
  }
}
