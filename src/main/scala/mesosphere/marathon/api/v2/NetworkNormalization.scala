package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.{ Network, NetworkMode }

object NetworkNormalization {

  import NetworkNormalizationMessages._

  /** dynamic network normalization configuration, useful for migration and/or testing */
  trait Config {
    def defaultNetworkName: Option[String]
  }

  case class Configure(
    override val defaultNetworkName: Option[String]
  ) extends Config

  case class Networks(config: Config, networks: Option[Seq[Network]])

  object Networks {
    implicit val normalizedNetworks: Normalization[Networks] = Normalization { n =>
      // IMPORTANT: only evaluate config.defaultNetworkName if we actually need it
      n.copy(networks = n.networks.map{ networks =>
        networks.map {
          case x: Network if x.name.isEmpty && x.mode == NetworkMode.Container => x.copy(name = n.config.defaultNetworkName)
          case x => x
        }
      })
    }
  }

  def requireContainerNetworkNameResolution(networks: Seq[Network]): Unit = {
    // sanity check: normalization should have populated missing container network names
    networks.find { net =>
      net.mode == NetworkMode.Container && net.name.isEmpty
    }.foreach(_ => throw NormalizationException(ContainerNetworkNameUnresolved))
  }
}

object NetworkNormalizationMessages {
  val ContainerNetworkNameUnresolved = "container network name is not defined"
}
