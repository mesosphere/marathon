package mesosphere.marathon
package core.pod

import mesosphere.marathon.plugin.NetworkSpec
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.Protos.NetworkDefinition
import org.apache.mesos.{ Protos => Mesos }
import mesosphere.mesos.protos.Implicits._

/**
  * Network declared by a [[PodDefinition]].
  */
sealed trait Network extends Product with Serializable with NetworkSpec

case object HostNetwork extends Network {
  val labels: Map[String, String] = Map.empty // implements NetworkSpec
}

case class ContainerNetwork(name: String, labels: Map[String, String] = Network.DefaultLabels) extends Network

/** specialized container network that uses a default bridge (containerizer dependent) */
case class BridgeNetwork(labels: Map[String, String] = Network.DefaultLabels) extends Network

object Network {

  implicit class NetworkHelper(val networks: Seq[Network]) extends AnyVal {
    def hasNonHostNetworking = networks.exists(_ != HostNetwork)
    def hasBridgeNetworking = networks.exists {
      case _: BridgeNetwork => true
      case _ => false
    }
    def hasContainerNetworking = networks.exists {
      case _: ContainerNetwork => true
      case _ => false
    }
  }

  val DefaultLabels: Map[String, String] = Map.empty

  /** @return deserialized Network, only None if network type is `UNKNOWN` (should never happen in practice) */
  def fromProto(net: NetworkDefinition): Option[Network] = {
    import NetworkDefinition.Mode._

    def labelsFromProto: Map[String, String] = net.getLabelsList.toSeq.fromProto

    net.getMode() match {
      case UNKNOWN =>
        None
      case HOST =>
        Some(HostNetwork)
      case CONTAINER =>
        val name =
          if (net.hasName) net.getName
          else throw new IllegalStateException("missing container name in NetworkDefinition")
        Some(ContainerNetwork(name, labelsFromProto))
      case BRIDGE =>
        Some(BridgeNetwork(labelsFromProto))
    }
  }

  def toProto(net: Network): NetworkDefinition = {
    val builder = NetworkDefinition.newBuilder()
    net match {
      case HostNetwork =>
        builder.setMode(NetworkDefinition.Mode.HOST)
      case br: BridgeNetwork =>
        builder
          .setMode(NetworkDefinition.Mode.BRIDGE)
          .addAllLabels(br.labels.map{ case (k, v) => Mesos.Label.newBuilder().setKey(k).setValue(v).build() })
      case ct: ContainerNetwork =>
        builder
          .setMode(NetworkDefinition.Mode.CONTAINER)
          .setName(ct.name)
          .addAllLabels(ct.labels.map{ case (k, v) => Mesos.Label.newBuilder().setKey(k).setValue(v).build() })
    }
    builder.build()
  }
}
