package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.{AnyToRaml, Endpoint, Network, NetworkMode, Pod, PodContainer, PodPersistentVolume, PodSchedulingPolicy, PodUpgradeStrategy}
import mesosphere.marathon.stream.Implicits._

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

  /**
    * If a pod has one or more persistent volumes, this method ensure that
    * the pod's upgrade and unreachable strategies have values which make
    * sense for resident pods: the unreachable strategy should be disabled
    * and the upgrade strategy has to have maximumOverCapacity set to 0
    * for such pods.
    *
    * @param pod a pod which scheduling policy should be normalized
    * @return a normalized scheduling policy
    */
  def normalizeScheduling(pod: Pod): Option[PodSchedulingPolicy] = {
    val hasPersistentVolumes = pod.volumes.existsAn[PodPersistentVolume]
    if (hasPersistentVolumes) {
      val defaultUpgradeStrategy = state.UpgradeStrategy.forResidentTasks.toRaml
      val defaultUnreachableStrategy = state.UnreachableStrategy.default(hasPersistentVolumes).toRaml
      val scheduling = pod.scheduling.getOrElse(PodSchedulingPolicy())
      val upgradeStrategy = scheduling.upgrade.getOrElse(PodUpgradeStrategy(
        minimumHealthCapacity = defaultUpgradeStrategy.minimumHealthCapacity,
        maximumOverCapacity = defaultUpgradeStrategy.maximumOverCapacity))
      val unreachableStrategy = scheduling.unreachableStrategy.getOrElse(defaultUnreachableStrategy)
      Some(scheduling.copy(
        upgrade = Some(upgradeStrategy),
        unreachableStrategy = Some(unreachableStrategy)))
    } else pod.scheduling
  }

  def apply(config: Config): Normalization[Pod] = Normalization { pod =>
    val networks = Networks(config, Some(pod.networks)).normalize.networks.filter(_.nonEmpty).getOrElse(DefaultNetworks)
    NetworkNormalization.requireContainerNetworkNameResolution(networks)
    val containers = Containers(networks, pod.containers).normalize.containers
    val scheduling = normalizeScheduling(pod)
    pod.copy(containers = containers, networks = networks, scheduling = scheduling)
  }
}
