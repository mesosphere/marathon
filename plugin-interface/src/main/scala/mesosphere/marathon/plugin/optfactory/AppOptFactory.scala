package mesosphere.marathon.plugin.optfactory

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin._

/** AppOptFactory generates functional options that configure objects of type T */
trait AppOptFactory[T] extends Opt.Factory[AppDefinition, T] with Plugin
