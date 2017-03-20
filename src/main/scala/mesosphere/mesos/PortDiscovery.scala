package mesosphere.mesos

import mesosphere.marathon.api.serialization.{ PortDefinitionSerializer, PortMappingSerializer }
import mesosphere.marathon.core.pod.HostNetwork
import mesosphere.marathon.raml.Endpoint
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.Protos.Port

import scala.collection.immutable.Seq

trait PortDiscovery {

  /**
    * @param hostModeNetworking is true if we're only using host networking (vs. bridged or container networking)
    * @param endpoints are assumed to have had wildcard ports (e.g. 0) filled in with actual port numbers
    */
  def generate(hostModeNetworking: Boolean, endpoints: Seq[Endpoint]): Seq[Port] =
    if (!hostModeNetworking) {
      // The run spec uses bridge and user modes with portMappings, use them to create the Port messages.
      // Just like apps, we prefer to generate network-scope=host when there's a hostPort available.
      endpoints.flatMap {
        case (ep @ Endpoint(_, Some(_), Some(hostPort), _, _)) =>
          val updatedEp = ep.copy(labels = ep.labels + NetworkScope.Host.discovery)
          PortMappingSerializer.toMesosPorts(updatedEp, hostPort)
        case (ep @ Endpoint(_, Some(containerPort), None, _, _)) =>
          val updatedEp = ep.copy(labels = ep.labels + NetworkScope.Container.discovery)
          PortMappingSerializer.toMesosPorts(updatedEp, containerPort)
        case ep =>
          throw new IllegalStateException(s"unexpected combination of network mode and endpoint ports for endpoint $ep")
      }(collection.breakOut)
    } else {
      // The port numbers are the allocated ports, we need to overwrite them the port numbers assigned to this particular task.
      // network-scope is assumed to be host, no need for an additional scope label here.
      endpoints.flatMap { ep =>
        val hostPort: Int = ep.hostPort.getOrElse(throw new IllegalStateException(
          "expected non-empty host port in conjunction with host networking"
        ))
        PortMappingSerializer.toMesosPorts(ep, hostPort)
      }(collection.breakOut)
    }

  def generate(runSpec: AppDefinition, hostPorts: Seq[Option[Int]]): Seq[Port] = {
    if (runSpec.networks.exists(_ != HostNetwork)) {
      runSpec.container.withFilter(_.portMappings.nonEmpty).map { c =>
        // The run spec uses bridge and user modes with portMappings, use them to create the Port messages
        c.portMappings.zip(hostPorts).collect {
          case (portMapping, None) =>
            // No host port has been defined. See PortsMatcher.mappedPortRanges, use container port instead.
            val updatedPortMapping =
              portMapping.copy(labels = portMapping.labels + NetworkScope.Container.discovery)
            PortMappingSerializer.toMesosPort(updatedPortMapping, portMapping.containerPort)
          case (portMapping, Some(hostPort)) =>
            // When there's a host port, advertise that for discovery because it may result in better
            // network performance (using host ports may be faster than bridged/NATd ports).
            val updatedPortMapping = portMapping.copy(labels = portMapping.labels + NetworkScope.Host.discovery)
            PortMappingSerializer.toMesosPort(updatedPortMapping, hostPort)
        }
      }.getOrElse(Nil) // no port mappings **and** non-host networking? then you don't have ports...
    } else {
      // Serialize runSpec.portDefinitions to protos. The port numbers are the service ports, we need to
      // overwrite them the port numbers assigned to this particular task.
      // network-scope is assumed to be host, no need for an additional scope label here.
      runSpec.portDefinitions.zip(hostPorts).collect {
        case (portDefinition, Some(hostPort)) =>
          PortDefinitionSerializer.toMesosProto(portDefinition).map(_.toBuilder.setNumber(hostPort).build)
      }.flatten
    }
  }
}

object PortDiscovery extends PortDiscovery
