package mesosphere.marathon
package core.pod

import mesosphere.marathon.plugin.NetworkSpec

/**
  * Network declared by a [[PodDefinition]].
  */
trait Network extends Product with Serializable with NetworkSpec
