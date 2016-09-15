package mesosphere.marathon.core.pod

import mesosphere.marathon.raml.{ KVLabels, NetworkMode, Network => RAMLNetwork }

/**
  * Network declared by a [[PodDefinition]].
  */
sealed trait Network extends Product with Serializable {
  def toAPIObject: RAMLNetwork
}

case object HostNetwork extends Network {
  override def toAPIObject: RAMLNetwork = RAMLNetwork(
    name = None, mode = NetworkMode.Host, labels = Option.empty[KVLabels]
  )
}

case class ContainerNetwork(name: String, labels: Map[String, String] = Network.DefaultLabels) extends Network {
  override def toAPIObject: RAMLNetwork = RAMLNetwork(
    name = Some(name),
    mode = NetworkMode.Container,
    labels = if(labels.isEmpty) Option.empty[KVLabels] else Some(KVLabels(labels))
  )
}

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
