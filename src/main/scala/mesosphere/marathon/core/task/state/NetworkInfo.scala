package mesosphere.marathon
package core.task.state

import mesosphere.marathon.stream._
import mesosphere.marathon.state._
import org.apache.mesos

/**
  * Metadata about a task's networking information
  *
  * @param hasConfiguredIpAddress If the associated app definition has a configured IP address, set this to true.
  * @param hostPorts The hostPorts as taken originally from the accepted offer
  * @param effectiveIpAddress the task's effective IP address, computed from runSpec, hostName and ipAddresses
  * @param ipAddresses all associated IP addresses, computed from mesosStatus
  */
case class NetworkInfo(
    hasConfiguredIpAddress: Boolean,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String],
    ipAddresses: Option[Seq[mesos.Protos.NetworkInfo.IPAddress]]) {

  import NetworkInfo._

  // TODO(cleanup): this should be a val, but we currently don't have the app when updating a task with a TaskStatus
  def portAssignments(app: AppDefinition): Seq[PortAssignment] =
    computePortAssignments(app, hostPorts, effectiveIpAddress)

  /**
    * Update the network info with the given mesos TaskStatus. This will eventually update ipAddresses and the
    * effectiveIpAddress.
    */
  def update(mesosStatus: mesos.Protos.TaskStatus) = {
    val newIpAddresses = resolveIpAddresses(mesosStatus)

    if (ipAddresses != newIpAddresses) {
      val newEffectiveIpAddress = if (hasConfiguredIpAddress) {
        firstIpAddressFrom(newIpAddresses)
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
  val empty = new NetworkInfo(hasConfiguredIpAddress = false, hostPorts = Nil, effectiveIpAddress = None, ipAddresses = None)

  private[state] def firstIpAddressFrom(ipAddresses: Option[Seq[mesos.Protos.NetworkInfo.IPAddress]]): Option[String] =
    ipAddresses.flatMap(_.headOption).map(_.getIpAddress)

  def apply(
    runSpec: RunSpec,
    hostName: String,
    hostPorts: Seq[Int],
    ipAddresses: Option[Seq[mesos.Protos.NetworkInfo.IPAddress]]): NetworkInfo = {

    val hasConfiguredIpAddress: Boolean = runSpec match {
      case app: AppDefinition if app.ipAddress.isDefined => true
      case _ => false
    }

    val effectiveIpAddress = if (hasConfiguredIpAddress) {
      firstIpAddressFrom(ipAddresses)
    } else {
      // TODO(PODS) extract ip address from launched task
      Some(hostName)
    }

    new NetworkInfo(hasConfiguredIpAddress, hostPorts, effectiveIpAddress, ipAddresses)
  }

  private[state] def resolveIpAddresses(mesosStatus: mesos.Protos.TaskStatus): Option[Seq[mesos.Protos.NetworkInfo.IPAddress]] = {
    if (mesosStatus.hasContainerStatus && mesosStatus.getContainerStatus.getNetworkInfosCount > 0) {
      Some(mesosStatus.getContainerStatus.getNetworkInfosList.flatMap(_.getIpAddressesList)(collection.breakOut))
    } else {
      None
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

    @SuppressWarnings(Array("OptionGet", "TraversableHead"))
    def fromPortMappings(container: Container): Seq[PortAssignment] =
      container.portMappings.map { portMapping =>
        val hostPort: Option[Int] =
          if (portMapping.hostPort.isEmpty) {
            None
          } else {
            val hostPort = hostPorts.head
            Some(hostPort)
          }

        val effectivePort =
          if (app.ipAddress.isDefined || portMapping.hostPort.isEmpty) {
            portMapping.containerPort
          } else {
            hostPort.get
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
