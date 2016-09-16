package mesosphere.marathon.core.pod

/**
  * Network declared by a [[PodDefinition]].
  */
sealed trait Network extends Product with Serializable

case object HostNetwork extends Network

case class ContainerNetwork(name: String, labels: Map[String, String] = Network.DefaultLabels) extends Network

object Network {
  val DefaultLabels: Map[String, String] = Map.empty
}
