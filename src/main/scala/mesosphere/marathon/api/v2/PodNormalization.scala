package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.{AnyToRaml, Endpoint, Network, NetworkMode, Pod, PodContainer, PodPersistentVolume, PodPlacementPolicy, PodSchedulingPolicy, PodUpgradeStrategy}
import mesosphere.marathon.util.RoleSettings
import mesosphere.marathon.stream.Implicits.toRichIterable

object PodNormalization {

  val DefaultNetworks = Seq.empty[Network]
  val DefaultHostPort = 0

  import NetworkNormalization.Networks
  import Normalization._

  /** dynamic pod normalization configuration, useful for migration and/or testing */
  trait Config extends NetworkNormalization.Config {
    def roleSettings: RoleSettings
    def sanitizeAcceptedResourceRoles: Boolean
  }

  case class Configuration(
      defaultNetworkName: Option[String],
      roleSettings: RoleSettings,
      sanitizeAcceptedResourceRoles: Boolean) extends Config

  object Configuration {
    def apply(config: MarathonConf, roleSettings: RoleSettings): Config =
      Configuration(
        config.defaultNetworkName.toOption,
        roleSettings,
        config.availableDeprecatedFeatures.isEnabled(DeprecatedFeatures.sanitizeAcceptedResourceRoles))
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
    * Removes all roles that are not the service role or "*" from the placement policy.
    *
    * @param maybePlacement The placement policy containing the accepted resource roles.
    * @param effectiveRole The final Mesos role of the pod.
    * @return the placement policy with update accepted resource roles.
    */
  def sanitizeAcceptedResourceRoles(maybePlacement: Option[PodPlacementPolicy], effectiveRole: String): Option[PodPlacementPolicy] = {
    maybePlacement.map { placement =>
      val sanitizedAcceptedResourceRoles = placement.acceptedResourceRoles.filter(role => role == "*" || role == effectiveRole)
      placement.copy(acceptedResourceRoles = sanitizedAcceptedResourceRoles)
    }
  }

  def normalizeUpgradeAndUnreachableStrategy(pod: Pod): PodSchedulingPolicy = {
    val defaultUpgradeStrategy = state.UpgradeStrategy.forResidentTasks.toRaml
    val defaultUnreachableStrategy = state.UnreachableStrategy.default(true).toRaml
    val scheduling = pod.scheduling.getOrElse(PodSchedulingPolicy())
    val upgradeStrategy = scheduling.upgrade.getOrElse(PodUpgradeStrategy(
      minimumHealthCapacity = defaultUpgradeStrategy.minimumHealthCapacity,
      maximumOverCapacity = defaultUpgradeStrategy.maximumOverCapacity))
    val unreachableStrategy = scheduling.unreachableStrategy.getOrElse(defaultUnreachableStrategy)
    scheduling.copy(
      upgrade = Some(upgradeStrategy),
      unreachableStrategy = Some(unreachableStrategy))
  }

  /**
    * If a pod has one or more persistent volumes, this method ensure that
    * the pod's upgrade and unreachable strategies have values which make
    * sense for resident pods: the unreachable strategy should be disabled
    * and the upgrade strategy has to have maximumOverCapacity set to 0
    * for such pods.
    *
    * @param pod a pod which scheduling policy should be normalized
    * @param effectiveRole the final Mesos role of the pod
    * @param config the normalization configuration
    * @return a normalized scheduling policy
    */
  def normalizeScheduling(pod: Pod, effectiveRole: String, config: Config): Option[PodSchedulingPolicy] = {
    val hasPersistentVolumes = pod.volumes.existsAn[PodPersistentVolume]
    val normalized = if (hasPersistentVolumes) {
      Some(normalizeUpgradeAndUnreachableStrategy(pod))
    } else pod.scheduling

    // sanitize accepted resource roles if enabled
    if (config.sanitizeAcceptedResourceRoles) {
      normalized.map { scheduling =>
        scheduling.copy(placement = sanitizeAcceptedResourceRoles(scheduling.placement, effectiveRole))
      }
    } else normalized
  }

  def apply(config: Config): Normalization[Pod] = Normalization { pod =>
    val networks = Networks(config, Some(pod.networks)).normalize.networks.filter(_.nonEmpty).getOrElse(DefaultNetworks)
    NetworkNormalization.requireContainerNetworkNameResolution(networks)
    val containers = Containers(networks, pod.containers).normalize.containers
    val role = pod.role.getOrElse(config.roleSettings.defaultRole)

    val scheduling = normalizeScheduling(pod, role, config)

    pod.copy(containers = containers, networks = networks, scheduling = scheduling, role = Some(role))
  }
}
