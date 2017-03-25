package mesosphere.marathon
package core.task.state

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.state._
import org.apache.mesos

import scala.annotation.tailrec

/**
  * Metadata about a task's networking information.
  *
  * @param hostPorts The hostPorts as taken originally from the accepted offer
  * @param hostName the agent's hostName
  * @param ipAddresses all associated IP addresses, computed from mesosStatus
  */
case class NetworkInfo(
    hostName: String,
    hostPorts: Seq[Int],
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]) {

  import NetworkInfo._

  /**
    * compute the effective IP address based on whether the runSpec declares container-mode networking; if so
    * then choose the first address from the list provided by Mesos. Otherwise, in host- and bridge-mode
    * networking just use the agent hostname as the effective IP.
    *
    * we assume that container-mode networking is exclusive of bridge-mode networking.
    */
  def effectiveIpAddress(runSpec: RunSpec): Option[String] = {
    if (runSpec.networks.hasContainerNetworking) {
      pickFirstIpAddressFrom(ipAddresses)
    } else {
      Some(hostName)
    }
  }

  /**
    * generate a list of possible port assignments, perhaps even including assignments for which no effective
    * address or port is available. A returned `PortAssignment` for which there is no `effectiveAddress` will have
    * have an `effectivePort` of `NoPort`.
    *
    * @param app the app run specification
    * @param includeUnresolved when `true` include assignments without effective address and port
    */
  def portAssignments(app: AppDefinition, includeUnresolved: Boolean): Seq[PortAssignment] = {
    computePortAssignments(app, hostName, hostPorts, effectiveIpAddress(app), includeUnresolved)
  }

  /**
    * Update the network info with the given mesos TaskStatus. This will eventually update ipAddresses and the
    * effectiveIpAddress.
    *
    * Note: Only makes sense to call this the task just became running as the reported ip addresses are not
    * expected to change during a tasks lifetime.
    */
  def update(mesosStatus: mesos.Protos.TaskStatus): NetworkInfo = {
    val newIpAddresses = resolveIpAddresses(mesosStatus)

    if (ipAddresses != newIpAddresses) {

      copy(ipAddresses = newIpAddresses)
    } else {
      // nothing has changed
      this
    }
  }
}

object NetworkInfo extends StrictLogging {

  /**
    * Pick the IP address based on an ip address configuration as given in teh AppDefinition
    *
    * Only applicable if the app definition defines an IP address. PortDefinitions cannot be configured in addition,
    * and we currently expect that there is at most one IP address assigned.
    */
  private[state] def pickFirstIpAddressFrom(ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): Option[String] = {
    // Explicitly take the ipAddress from the first given object, if available. We do not expect to receive
    // IPAddresses that do not define an ipAddress.
    ipAddresses.headOption.map { ipAddress =>
      require(ipAddress.hasIpAddress, s"$ipAddress does not define an ipAddress")
      ipAddress.getIpAddress
    }
  }

  def resolveIpAddresses(mesosStatus: mesos.Protos.TaskStatus): Seq[mesos.Protos.NetworkInfo.IPAddress] = {
    if (mesosStatus.hasContainerStatus && mesosStatus.getContainerStatus.getNetworkInfosCount > 0) {
      mesosStatus.getContainerStatus.getNetworkInfosList.flatMap(_.getIpAddressesList)(collection.breakOut)
    } else {
      Nil
    }
  }

  private def computePortAssignments(
    app: AppDefinition,
    hostName: String,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String],
    includeUnresolved: Boolean): Seq[PortAssignment] = {

    def fromPortMappings(container: Container): Seq[PortAssignment] = {
      import Container.PortMapping
      @tailrec
      def gen(ports: List[Int], mappings: List[PortMapping], assignments: List[PortAssignment]): List[PortAssignment] = {
        (ports, mappings) match {
          case (hostPort :: xs, PortMapping(containerPort, Some(_), _, _, portName, _) :: rs) =>
            // agent port was requested, and we strongly prefer agentIP:hostPort (legacy reasons?)
            val assignment = PortAssignment(
              portName = portName,
              effectiveIpAddress = Option(hostName),
              effectivePort = hostPort,
              hostPort = Option(hostPort),
              containerPort = Option(containerPort)
            )
            gen(xs, rs, assignment :: assignments)
          case (_, mapping :: rs) if mapping.hostPort.isEmpty =>
            // no port was requested on the agent (really, this is only possible for container networking)
            val assignment = PortAssignment(
              portName = mapping.name,
              // if there's no assigned IP and we have no host port, then this container isn't reachable
              effectiveIpAddress = effectiveIpAddress,
              // just pick containerPort; we don't have an agent port to fall back on regardless,
              // of effectiveIp or hasAssignedIpAddress
              effectivePort = effectiveIpAddress.fold(PortAssignment.NoPort)(_ => mapping.containerPort),
              hostPort = None,
              containerPort = Some(mapping.containerPort)
            )
            gen(ports, rs, assignment :: assignments)
          case (Nil, Nil) =>
            assignments
          case _ =>
            throw new IllegalStateException(
              s"failed to align remaining allocated host ports $ports with remaining declared port mappings $mappings")
        }
      }
      gen(hostPorts.to[List], container.portMappings.to[List], Nil).reverse
    }

    def fromPortDefinitions: Seq[PortAssignment] =
      app.portDefinitions.zip(hostPorts).map {
        case (portDefinition, hostPort) =>
          PortAssignment(
            portName = portDefinition.name,
            effectiveIpAddress = effectiveIpAddress,
            effectivePort = hostPort,
            hostPort = Some(hostPort))
      }

    app.container.collect {
      case c: Container if app.networks.hasNonHostNetworking =>
        // don't return assignments that haven't yet been allocated a port
        val mappings = fromPortMappings(c)
        if (includeUnresolved) mappings else mappings.filter(_.isResolved)
    }.getOrElse(fromPortDefinitions)
  }
}
