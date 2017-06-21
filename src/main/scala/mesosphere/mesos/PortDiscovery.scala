package mesosphere.mesos

import mesosphere.marathon.api.serialization.PortMappingSerializer
import mesosphere.marathon.raml.Endpoint
import mesosphere.marathon.state.{ AppDefinition, PortDefinition }
import mesosphere.marathon.state.Container.PortMapping
import org.apache.mesos.Protos.Port
import mesosphere.marathon.core.pod.Network

import scala.collection.immutable.Seq

object PortDiscovery {
  /**
    * Generic interface to read either an EndPoint or a PortMapping. This allows us to write generically against these
    * two separate, but similar, data structures.
    *
    * TODO - extract and use more widely to fold away duplicate code paths dealing with these two separate but similar
    * data structures
    */
  private trait PortMappingReader[T] {
    def name(t: T): Option[String]
    def labels(t: T): Map[String, String]
    def protocols(t: T): Seq[String]
    def networkNames(t: T): Seq[String]
    def containerPort(t: T): Option[Int]
    def hostPort(t: T): Option[Int]
  }
  private object PortMappingReader {
    implicit object EndpointReader extends PortMappingReader[Endpoint] {
      def name(ep: Endpoint) = Some(ep.name)
      def labels(ep: Endpoint) = ep.labels
      def protocols(ep: Endpoint) = ep.protocol
      def networkNames(ep: Endpoint) = ep.networkNames
      def containerPort(ep: Endpoint) = ep.containerPort
      def hostPort(ep: Endpoint) = ep.hostPort
    }

    implicit object ContainerPortMappingReader extends PortMappingReader[PortMapping] {
      def name(portMapping: PortMapping) = portMapping.name
      def labels(portMapping: PortMapping) = portMapping.labels
      def protocols(portMapping: PortMapping) = portMapping.protocol.split(',').to[Seq]
      def networkNames(portMapping: PortMapping) = portMapping.networkNames
      def containerPort(portMapping: PortMapping) = Some(portMapping.containerPort)
      def hostPort(portMapping: PortMapping) = portMapping.hostPort
    }

    implicit object PortDefinitionReader extends PortMappingReader[PortDefinition] {
      def name(portDefinition: PortDefinition) = portDefinition.name
      def labels(portDefinition: PortDefinition) = portDefinition.labels
      def protocols(portDefinition: PortDefinition) = portDefinition.protocol.split(',').to[Seq]
      def networkNames(portDefinition: PortDefinition) = Nil
      def containerPort(portDefinition: PortDefinition) = None
      def hostPort(portDefinition: PortDefinition) = Some(portDefinition.port)
    }
  }

  /**
    * Generate mesos Port discovery proto for each PortMapping / Endpoint entry in order to facilitate port discovery
    * via mesos.
    *
    * If hostPort is nonEmpty, generate a single record with the network-scope of "host"
    *
    * Otherwise, generate a record for the cartesian expansion of every protocol AND associated container network. For
    * example, if an entry specifies two protocols and 3 container networks, then we generate 6 mesos Port proto
    * records. Records have a network-scope of "container", and, additionally, specify the associated container network
    * via the network-name label.
    *
    * @param networks The networks associated with the appSpec / pod
    * @param endpoints Seq of Endpoint or container PortMapping
    *
    * @return Seq of mesos Port proto records, intended to be used to support port discovery via mesos
    */
  def generate[T](networks: Seq[Network], mappings: Seq[T])(implicit r: PortMappingReader[T]): Seq[Port] = {
    if (!networks.isHostModeNetworking) {
      // The run spec uses bridge and user modes with portMappings, use them to create the Port messages.
      // Just like apps, we prefer to generate network-scope=host when there's a hostPort available.
      mappings.flatMap { mp =>
        (r.containerPort(mp), r.hostPort(mp)) match {
          case (Some(_), Some(hostPort)) =>
            r.protocols(mp).map { protocol =>
              PortMappingSerializer.toMesosPort(
                name = r.name(mp),
                labels = r.labels(mp) ++ discoveryLabelsHost,
                protocol = protocol,
                effectivePort = hostPort)
            }
          case (Some(containerPort), None) =>
            for {
              protocol <- r.protocols(mp)
              networkName <- if (r.networkNames(mp).isEmpty) networks.containerNetworkNames else r.networkNames(mp)
            } yield {
              PortMappingSerializer.toMesosPort(
                name = r.name(mp),
                labels = r.labels(mp) ++ discoveryLabelsContainer(networkName),
                protocol = protocol,
                effectivePort = containerPort)
            }
          case _ =>
            throw new IllegalStateException(
              s"unexpected combination of network mode and endpoint ports for ${mp.getClass.getSimpleName} $mp")
        }
      }(collection.breakOut)
    } else {
      // network-scope is assumed to be host, no need for an additional scope label here.
      for {
        ep <- mappings

        hostPort: Int = r.hostPort(ep).getOrElse(throw new IllegalStateException(
          "expected non-empty host port in conjunction with host networking"))

        protocol <- r.protocols(ep)
      } yield PortMappingSerializer.toMesosPort(
        name = r.name(ep),
        labels = r.labels(ep),
        protocol = protocol,
        effectivePort = hostPort)
    }
  }

  /**
    * See [[generate]]
    *
    * @param networks the networks specified for the pod
    * @param endpoints are assumed to have had wildcard ports (e.g. 0) filled in with actual port numbers
    */
  def generateForPod(networks: Seq[Network], endpoints: Seq[Endpoint]): Seq[Port] =
    generate(networks, endpoints)

  /**
    * See [[generate]]
    *
    * @param hostPorts the hostPorts after resource matching
    */
  def generateForApp(runSpec: AppDefinition, hostPorts: Seq[Option[Int]]): Seq[Port] =
    if (!runSpec.networks.isHostModeNetworking) {
      generate(
        runSpec.networks,
        runSpec.container
          .fold(Seq.empty[(PortMapping, Option[Int])]) { c => c.portMappings.zip(hostPorts) }
          .map {
            case (portMapping, assignment) =>
              if (portMapping.hostPort.isEmpty != assignment.isEmpty)
                throw new IllegalStateException(
                  s"unsupported combination of portMapping ${portMapping} and host port allocation")
              else
                portMapping.copy(hostPort = assignment)
          })
    } else {
      // The port numbers are the allocated ports, we need to overwrite them the port numbers assigned to this particular task.
      generate(
        runSpec.networks,
        runSpec.portDefinitions.zip(hostPorts).map {
          case (portDefinition, Some(assignment)) =>
            portDefinition.copy(port = assignment)
          case _ =>
            throw new IllegalStateException(
              "illegal portDefinition without host assignment")
        })

    }
  val NetworkScopeHost = "host"
  val NetworkScopeContainer = "container"

  val NetworkScopeLabel = "network-scope"
  val NetworkNameLabel = "network-name"

  /**
    * @return Seq of appropriate network discovery labels for network-scope (all networks) and network-name (container
    *         networks)
    */
  private val discoveryLabelsHost: Seq[(String, String)] =
    Seq(NetworkScopeLabel -> NetworkScopeHost)

  private def discoveryLabelsContainer(networkName: String): Seq[(String, String)] =
    Seq(
      NetworkScopeLabel -> NetworkScopeContainer,
      NetworkNameLabel -> networkName)
}
