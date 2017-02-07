package mesosphere.marathon
package core.pod

import mesosphere.marathon.plugin.NetworkSpec

/**
  * Network declared by a [[PodDefinition]].
  */
sealed trait Network extends Product with Serializable with NetworkSpec

case object HostNetwork extends Network {
  val labels: Map[String, String] = Map.empty // implements NetworkSpec
}

case class ContainerNetwork(name: String, labels: Map[String, String] = Network.DefaultLabels) extends Network

object Network {
  val DefaultLabels: Map[String, String] = Map.empty
}
