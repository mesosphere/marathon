package mesosphere.marathon.core.pod

import mesosphere.marathon.raml.{ NetworkMode, Network => RAMLNetwork }

/**
  * Network declared by a [[PodDefinition]].
  */
sealed trait Network extends Product with Serializable

case object HostNetwork extends Network

case class ContainerNetwork(name: String, labels: Map[String, String] = Network.DefaultLabels) extends Network

object Network {

  val DefaultLabels: Map[String,String] = Map.empty

  def apply(network: RAMLNetwork): Network = network.mode match {
    case NetworkMode.Host => HostNetwork
    case NetworkMode.Container => ContainerNetwork(
      network.name.getOrElse(throw new IllegalArgumentException("container network must specify a name")),
      network.labels.map(_.values).getOrElse(DefaultLabels)
    )
  }
}
