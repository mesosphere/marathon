package mesosphere.marathon
package core.task.state

import mesosphere.marathon.stream._
import mesosphere.marathon.state._
import org.apache.mesos

/**
  * Metadata about a task's networking information
  *
  * @param hasConfiguredIpAddress True if the associated app definition has configured an IP address
  * @param hostPorts The hostPorts as taken originally from the accepted offer
  * @param effectiveIpAddress the task's effective IP address, computed from runSpec, hostName and ipAddresses
  * @param ipAddresses all associated IP addresses, computed from mesosStatus
  */
case class NetworkInfo(
    hasConfiguredIpAddress: Boolean,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String],
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]) {

  import NetworkInfo._

  // TODO(cleanup): this should be a val, but we currently don't have the app when updating a task with a TaskStatus
  def portAssignments(app: AppDefinition): Seq[PortAssignment] =
    computePortAssignments(app, hostPorts, effectiveIpAddress)

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
      val newEffectiveIpAddress = if (hasConfiguredIpAddress) {
        pickFirstIpAddressFrom(newIpAddresses)
      } else {
        effectiveIpAddress
      }
      copy(ipAddresses = newIpAddresses, effectiveIpAddress = newEffectiveIpAddress)
    } else {
      // nothing has changed
      this
    }
  }
}

object NetworkInfo {
  val empty: NetworkInfo = new NetworkInfo(hasConfiguredIpAddress = false, hostPorts = Nil, effectiveIpAddress = None, ipAddresses = Nil)

  /**
    * Pick the IP address based on an ip address configuration as given in teh AppDefinition
    *
    * Only applicable if the app definition defines an IP address. PortDefinitions cannot be configured in addition,
    * and we currently expect that there is at most one IP address assigned.
    */
  private[state] def pickFirstIpAddressFrom(ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): Option[String] = {
    ipAddresses.headOption.map(_.getIpAddress)
  }

  def apply(
    runSpec: RunSpec,
    hostName: String,
    hostPorts: Seq[Int],
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): NetworkInfo = {

    val hasConfiguredIpAddress: Boolean = runSpec match {
      case app: AppDefinition => app.ipAddress.isDefined
      case _ => false
    }

    val effectiveIpAddress = if (hasConfiguredIpAddress) {
      pickFirstIpAddressFrom(ipAddresses)
    } else {
      // TODO(PODS) extract ip address from launched task
      Some(hostName)
    }

    new NetworkInfo(hasConfiguredIpAddress, hostPorts, effectiveIpAddress, ipAddresses)
  }

  private[state] def resolveIpAddresses(mesosStatus: mesos.Protos.TaskStatus): Seq[mesos.Protos.NetworkInfo.IPAddress] = {
    if (mesosStatus.hasContainerStatus && mesosStatus.getContainerStatus.getNetworkInfosCount > 0) {
      mesosStatus.getContainerStatus.getNetworkInfosList.flatMap(_.getIpAddressesList)(collection.breakOut)
    } else {
      Nil
    }
  }

  private[state] def computePortAssignments(
    app: AppDefinition,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String]): Seq[PortAssignment] = {

    def fromDiscoveryInfo: Seq[PortAssignment] = app.ipAddress.map {
      case IpAddress(_, _, DiscoveryInfo(appPorts), _) =>
        appPorts.zip(hostPorts).map {
          case (appPort, hostPort) =>
            PortAssignment(
              portName = Some(appPort.name),
              effectiveIpAddress = effectiveIpAddress,
              effectivePort = hostPort,
              hostPort = Some(hostPort))
        }
    }.getOrElse(Nil)

    @SuppressWarnings(Array("TraversableHead"))
    def fromPortMappings(container: Container): Seq[PortAssignment] =
      container.portMappings.map { portMapping =>
        val hostPort: Option[Int] =
          if (portMapping.hostPort.isEmpty) {
            None
          } else {
            hostPorts.headOption
          }

        val effectivePort =
          if (app.ipAddress.isDefined || portMapping.hostPort.isEmpty) {
            portMapping.containerPort
          } else {
            hostPort.getOrElse {
              throw new IllegalStateException(s"Unable to compute effectivePort for $container")
            }
          }

        PortAssignment(
          portName = portMapping.name,
          effectiveIpAddress = effectiveIpAddress,
          effectivePort = effectivePort,
          hostPort = hostPort,
          containerPort = Some(portMapping.containerPort))
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
      // TODO(portMappings) support other container types (bridge and user modes are docker-specific)
      case c: Container if app.networkModeBridge || app.networkModeUser => fromPortMappings(c)
    }.getOrElse(if (app.ipAddress.isDefined) fromDiscoveryInfo else fromPortDefinitions)
  }

}
