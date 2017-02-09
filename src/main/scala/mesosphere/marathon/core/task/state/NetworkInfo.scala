package mesosphere.marathon
package core.task.state

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.state._
import org.apache.mesos

/**
  * Metadata about a task's networking information
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

  def hasConfiguredIpAddress(runSpec: RunSpec): Boolean = {
    runSpec match {
      case app: AppDefinition => app.ipAddress.isDefined
      case _ =>
        // TODO(pods): consumers of [[effectiveIpAddress]] are biased towards apps; make this work for pods too
        false
    }
  }

  def effectiveIpAddress(runSpec: RunSpec): Option[String] = {
    if (hasConfiguredIpAddress(runSpec)) {
      pickFirstIpAddressFrom(ipAddresses)
    } else {
      Some(hostName)
    }
  }

  // TODO(cleanup): this should be a val, but we currently don't have the app when updating a task with a TaskStatus
  def portAssignments(app: AppDefinition): Seq[PortAssignment] = {
    computePortAssignments(app, hostPorts, effectiveIpAddress(app))
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
  private[task] def pickFirstIpAddressFrom(ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): Option[String] = {
    // Explicitly take the ipAddress from the first given object, if available. We do not expect to receive
    // IPAddresses that do not define an ipAddress.
    ipAddresses.headOption.map { ipAddress =>
      require(ipAddress.hasIpAddress, s"$ipAddress does not define an ipAddress")
      ipAddress.getIpAddress
    }
  }

  private[task] def resolveIpAddresses(mesosStatus: mesos.Protos.TaskStatus): Seq[mesos.Protos.NetworkInfo.IPAddress] = {
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

    def fromPortMappings(container: Container): Seq[PortAssignment] = {
      var availableHostPorts = hostPorts
      container.portMappings.map { portMapping =>
        // if the portAssignment as configured on the app requests a hostPort, take the next one from the list of hostPorts
        val hostPort: Option[Int] = portMapping.hostPort.flatMap { _ =>
          availableHostPorts.headOption.map { nextAvailablePort =>
            // update the list of available hostPorts to the remaining tail
            availableHostPorts = availableHostPorts.tail
            logger.debug(s"assigned $nextAvailablePort for $portMapping")
            nextAvailablePort
          }
        }.orElse(None)

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
