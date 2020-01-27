package mesosphere.marathon
package api

import mesosphere.marathon.api.v2.MarathonCompatibility
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.HostNetwork
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Container.PortMapping

object EndpointsHelper {
  sealed trait Error { val msg: String }
  case object MarathonCompatibilityNotEnabled extends Error {
    val msg = s"In order to use the flag compatibilityMode, 1.5 compatible task output must be enabled with --deprecated_features=${DeprecatedFeatures.marathon15Compatibility.key}."
  }
  case class CompatibilityModeNotValid(mode: String) extends Error {
    val msg = s"compatibilityMode ${mode} is invalid"
  }

  /**
    * When DeprecatedFeatures.marathon15Compatibility is removed, remove this dispatch function and collapse to just the single helper
    * @param data
    * @param maybeCompatibilityMode
    * @param marathon15CompatibilityEnabled
    * @return
    */
  def dispatchAppsToEndpoint(data: ListTasks, maybeCompatibilityMode: Option[String], marathon15CompatibilityEnabled: Boolean): Either[Error, String] = {
    val compatibilityMode = maybeCompatibilityMode.getOrElse { if (marathon15CompatibilityEnabled) MarathonCompatibility.V1_5 else MarathonCompatibility.Latest }
    (compatibilityMode, marathon15CompatibilityEnabled) match {
      case (MarathonCompatibility.Latest, _) =>
        Right(appsToEndpointString(data))
      case (_, false) =>
        Left(MarathonCompatibilityNotEnabled)
      case (MarathonCompatibility.V1_4, _) =>
        Right(MarathonCompatibility14.appsToEndpointString(data))
      case (MarathonCompatibility.V1_5, _) =>
        Right(MarathonCompatibility15.appsToEndpointString(data))
      case (o, _) =>
        Left(CompatibilityModeNotValid(o))
    }
  }

  object MarathonCompatibility14 {
    /**
      * Renders a text representation of tasks and their corresponding network ports, prioritized first by the host
      * network, and then, if a mapping is not available, the container ip and container port.
      *
      * @param data The tasks to render
      */
    def appsToEndpointString(data: ListTasks): String = {

      val delimiter = "\t"
      val sb = new StringBuilder
      val apps = data.apps
      val instancesMap = data.instancesMap

      apps.foreach { app =>
        val instances = instancesMap.specInstances(app.id)
        val cleanId = app.id.safePath

        val servicePorts = app.servicePorts

        if (servicePorts.isEmpty) {
          sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
          for (
            instance <- instances if instance.isRunning;
            agentInfo <- instance.agentInfo
          ) {
            sb.append(agentInfo.host).append(' ')
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
                  tryAppendContainerPort(sb, app, portMapping, instance, delimiter)
                }
              case Some(portMapping) if portMapping.hostPort.nonEmpty =>
                // the task hostPorts only contains an entry for each portMapping that has a hostPort defined
                // We need to compute and use the new index
                hostPortIndexOffset(app, i).foreach { computedHostPortIndex =>
                  runningInstances.foreach { task =>
                    appendHostPortOrZero(sb, task, computedHostPortIndex, delimiter)
                  }
                }
              case _ =>
                runningInstances.foreach { instance =>
                  appendHostPortOrZero(sb, instance, i, delimiter)
                }
            }
            sb.append('\n')
          }
        }
      }
      sb.toString()
    }

    /**
      * Append an entry to the provided string builder using the task's agent host IP and specified host port
      *
      * Remove this method when DeprecatedFeatures.marathon15Compatibility is fully removed
      *
      */
    def appendHostPortOrZero(sb: StringBuilder, instance: Instance, portIdx: Integer, delimiter: String): Unit = {
      instance.agentInfo.foreach { agentInfo =>
        instance.tasksMap.values.withFilter(_.status.condition.isActive).foreach { task =>
          val taskPort = task.status.networkInfo.hostPorts.lift(portIdx).getOrElse(0)
          sb.append(agentInfo.host).append(':').append(taskPort).append(delimiter)
        }
      }
    }
  }

  object MarathonCompatibility15 {
    /**
      * Produces a script-friendly string representation of the supplied
      * apps' tasks.  The data columns in the result are separated by
      * the supplied delimiter string.
      *
      * Generated line format is: * <pre>{app-id}{d}{service-port}{d}{address-list}</pre>.
      * `{service-port}` is `" "` for apps without service ports.
      * `{address-list}` is either `{host-list}` (for apps without service ports), or `{host-address-list}`.
      * `{host-list}` is a delimited list of agents that are running the task.
      * `{host-address-list}` is a delimited list of `{agent}:{hostPort}` tuples.
      * The contents of `{address-list}` are sorted for deterministic output.
      */
    def appsToEndpointString(data: ListTasks): String = {

      val delimiter = "\t"
      val sb = new StringBuilder
      val apps = data.apps
      val instancesMap = data.instancesMap

      apps.foreach { app =>
        val instances = instancesMap.specInstances(app.id)
        val cleanId = app.id.safePath

        val servicePorts = app.servicePorts

        if (servicePorts.isEmpty) {
          sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
          instances.collect { case Instance.Running(_, agentInfo, _) => agentInfo.host }
            .sorted.foreach { hostname =>
              sb.append(hostname).append(delimiter)
            }
          sb.append('\n')
        } else {
          servicePorts.zipWithIndex.foreach {
            case (port, i) =>
              sb.append(cleanId).append(delimiter).append(port).append(delimiter)
              instances.collect {
                case Instance.Running(_, agentInfo, tasksMap) =>
                  tasksMap.map {
                    case (_, task) =>
                      val taskPort = task.status.networkInfo.hostPorts.drop(i).headOption.getOrElse(0)
                      s"${agentInfo.host}:$taskPort"
                  }
              }.flatten.sorted.foreach { address =>
                sb.append(address).append(delimiter)
              }
              sb.append('\n')
          }
        }
      }
      sb.toString()
    }
  }

  /**
    * Renders a text representation of tasks and their corresponding network ports, prioritized first by the host
    * network, and then, if a mapping is not available, the container ip and container port.
    *
    * @param data The tasks to render
    */
  def appsToEndpointString(data: ListTasks): String = {

    val delimiter = "\t"
    val sb = new StringBuilder
    val apps = data.apps
    val instancesMap = data.instancesMap

    apps.foreach { app =>
      val instances = instancesMap.specInstances(app.id)
      val cleanId = app.id.safePath

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
              // At a future point we may wish to only return port definitions pertaining to certain network types; this should be added as a separate parameter.
              runningInstances.foreach { instance =>
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
  def tryAppendContainerPort(sb: StringBuilder, app: AppDefinition, portMapping: PortMapping, instance: Instance,
    delimiter: String): Unit = {
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
