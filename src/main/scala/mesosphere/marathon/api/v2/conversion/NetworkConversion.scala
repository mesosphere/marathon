package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.core.pod.{ ContainerNetwork, HostNetwork, Network }
import mesosphere.marathon.raml.{ KVLabels, NetworkMode, Network => RAMLNetwork }

trait NetworkConversion {

  import NetworkConversion._

  implicit val fromNetworkToAPIObject = Converter { network: Network =>
    network match {
      case cnet: ContainerNetwork =>
        Some(RAMLNetwork(
          name = Some(cnet.name),
          mode = NetworkMode.Container,
          labels = if (cnet.labels.isEmpty) Option.empty[KVLabels] else Some(KVLabels(cnet.labels))
        ))
      case HostNetwork => Some(RAMLNetwork(mode = NetworkMode.Host))
    }
  }

  implicit val fromAPIObjectToNetwork = Converter { network: RAMLNetwork =>
    Some(network.mode match {
      case NetworkMode.Host => HostNetwork
      case NetworkMode.Container => ContainerNetwork(
        network.name.getOrElse(throw new IllegalArgumentException("container network must specify a name")),
        network.labels.map(_.values).getOrElse(DefaultLabels)
      )
    })
  }
}

protected[this] object NetworkConversion {
  val DefaultLabels: Map[String,String] = Map.empty
}
