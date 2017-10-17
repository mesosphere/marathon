package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.{ Endpoint, Network, NetworkMode, Pod, PodContainer }

object PodNormalization {

  val DefaultNetworks = Seq.empty[Network]
  val DefaultHostPort = 0

  import NetworkNormalization.Networks
  import Normalization._

  /** dynamic pod normalization configuration, useful for migration and/or testing */
  trait Config extends NetworkNormalization.Config

  case class Configuration(nc: NetworkNormalization.Config) extends Config {
    override val defaultNetworkName: Option[String] = nc.defaultNetworkName
  }

  object Configuration {
    def apply(defaultNetworkName: Option[String]): Config =
      Configuration(NetworkNormalization.Configure(defaultNetworkName))
  }

  case class Containers(networks: Seq[Network], containers: Seq[PodContainer])

  object Containers {
    private val withDefaultHostPort = Normalization[Endpoint] { ep =>
      if (ep.hostPort.nonEmpty) ep else ep.copy(hostPort = Some(DefaultHostPort))
    }

    implicit val normalization: Normalization[Containers] = Normalization[Containers] { containers =>
      val isContainerMode = containers.networks.exists(_.mode == NetworkMode.Container)
      if (isContainerMode) {
        containers
      } else {
        containers.copy(containers = containers.containers.map { ct =>
          if (ct.endpoints.isEmpty) ct else ct.copy(endpoints = ct.endpoints.map(withDefaultHostPort.normalized))
        })
      }
    }
  }

  def apply(config: Config): Normalization[Pod] = Normalization { pod =>
    val networks = Networks(config, Some(pod.networks)).normalize.networks.filter(_.nonEmpty).getOrElse(DefaultNetworks)
    NetworkNormalization.requireContainerNetworkNameResolution(networks)
    val containers = Containers(networks, pod.containers).normalize.containers
    pod.copy(containers = containers, networks = networks)
  }
}
