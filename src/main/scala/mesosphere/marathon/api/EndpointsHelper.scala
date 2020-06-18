package mesosphere.marathon
package api

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ContainerNetwork, HostNetwork}
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Container.PortMapping

object EndpointsHelper {
  private[api] def parseNetworkPredicate(networkFilter: Set[String]): String => Boolean = {
    if (networkFilter contains "*") {
      { _ => true }
    } else {
      networkFilter
    }
  }

  /**
    * Renders a text representation of tasks and their corresponding network ports, prioritized first by the host
    * network, and then, if a mapping is not available, the container ip and container port.
    *
    * @param data The tasks to render
    * @param containerNetworks Set of container network names to include in the output. A network name of "*" indicates all networks should be included.
    */
  def appsToEndpointString(data: ListTasks, containerNetworks: Set[String]): String = {
    val delimiter = "\t"
    val sb = new StringBuilder
    val apps = data.apps
    val instancesMap = data.instancesMap

    val containerNetworkPredicate = parseNetworkPredicate(containerNetworks)

    apps.foreach { app =>
      val instances = instancesMap.specInstances(app.id)
      val cleanId = app.id.safePath
      val appContainerNetworkNames = app.networks.collect { case ContainerNetwork(name, _) => name }

      val servicePorts = app.servicePorts

      if (servicePorts.isEmpty) {
        sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
        for (
          instance <- instances if instance.isRunning;
          agentInfo <- instance.agentInfo
        ) {
          sb.append(agentInfo.host).append('\t')
        }
        sb.append('\n')
      } else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(cleanId).append(delimiter).append(port).append(delimiter)

          val ipPerTaskPortMapping = if (!app.networks.contains(HostNetwork)) containerPortMapping(app, i) else None
          val runningInstances = instances.withFilter(_.isRunning)
          ipPerTaskPortMapping match {
            // port definition with no hostPort: container network
            case Some(portMapping) if portMapping.hostPort.isEmpty =>
              runningInstances.foreach { instance =>
                val networkNames =
                  if (portMapping.networkNames.isEmpty)
                    appContainerNetworkNames
                  else
                    portMapping.networkNames
                // note: this excludes bridge-networks
                if (networkNames.exists(containerNetworkPredicate))
                  tryAppendContainerPort(sb, app, portMapping, instance, delimiter)
              }
            case Some(portMapping) if portMapping.hostPort.nonEmpty =>
              // the task hostPorts only contains an entry for each portMapping that has a hostPort defined
              // We need to compute and use the new index
              hostPortIndexOffset(app, i).foreach { computedHostPortIndex =>
                runningInstances.foreach { task =>
                  appendHostPort(sb, task, computedHostPortIndex, delimiter)
                }
              }
            case _ =>
              runningInstances.foreach { instance =>
                appendHostPort(sb, instance, i, delimiter)
              }
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

  def containerPortMapping(app: AppDefinition, portIdx: Integer): Option[PortMapping] =
    for {
      container <- app.container
      portMapping <- container.portMappings.lift(portIdx) // After MARATHON-7407 is addressed, this should probably throw.
    } yield portMapping

  /**
    * Append an entry to the provided string builder for the specified containerPort. If we cannot tell the
    * effectiveIpAddress, output nothing.
    */
  def tryAppendContainerPort(
      sb: StringBuilder,
      app: AppDefinition,
      portMapping: PortMapping,
      instance: Instance,
      delimiter: String
  ): Unit = {
    for {
      task <- instance.tasksMap.values
      address <- task.status.networkInfo.effectiveIpAddress(app)
    } {
      sb.append(address).append(':').append(portMapping.containerPort).append(delimiter)
    }
  }

  /**
    * Adjusts the index based on portMapping definitions. Expects that the specified index refers to a nonEmpty hostPort
    * portmapping record.
    */
  def hostPortIndexOffset(app: AppDefinition, idx: Integer): Option[Integer] = {
    app.container.flatMap { container =>
      val pm = container.portMappings
      if (idx < 0 || idx >= pm.length) // index 2, length 2 invalid
        None // linter:ignore:DuplicateIfBranches
      else if (pm(idx).hostPort.isEmpty)
        None
      else
        // count each preceeding nonEmpty hostPort to get new index
        Some(pm.toIterator.take(idx).count(_.hostPort.nonEmpty))
    }
  }

  /**
    * Append an entry to the provided string builder using the task's agent host IP and specified host port
    */
  def appendHostPort(sb: StringBuilder, instance: Instance, portIdx: Integer, delimiter: String): Unit = {
    for {
      agentInfo <- instance.agentInfo
      task <- instance.tasksMap.values if task.status.condition.isActive
      taskPort <- task.status.networkInfo.hostPorts.lift(portIdx)
    } {
      sb.append(agentInfo.host).append(':').append(taskPort).append(delimiter)
    }
  }

  case class ListTasks(instancesMap: InstancesBySpec, apps: Seq[AppDefinition])
}
