package mesosphere.marathon
package api

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.HostNetwork
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Container.PortMapping

object EndpointsHelper {
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
        instances.withFilter(_.isRunning).map(_.agentInfo.host).sorted.foreach { hostname =>
          sb.append(hostname).append(delimiter)
        }
        sb.append('\n')
      } else {
        servicePorts.zipWithIndex.foreach {
          case (port, i) =>
            sb.append(cleanId).append(delimiter).append(port).append(delimiter)
            instances.withFilter(_.isRunning).flatMap { instance =>
              instance.tasksMap.map {
                case (_, task) =>
                  val taskPort = task.status.networkInfo.hostPorts.drop(i).headOption.getOrElse(0)
                  s"${instance.agentInfo.host}:$taskPort"
              }
            }.sorted.foreach { address =>
              sb.append(address).append(delimiter)
            }
            sb.append('\n')
        }
      }
    }
    sb.toString()
  }

  def appsToEndpointStringCompatibleWith14(data: ListTasks): String = {

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
        for (instance <- instances if instance.isRunning) {
          sb.append(instance.agentInfo.host).append(' ')
        }
        sb.append('\n')
      } else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(cleanId).append(delimiter).append(port).append(delimiter)

          val ipPerTaskPortMapping = if (!app.networks.contains(HostNetwork)) CompatibilityWith14.containerPortMapping(app, i) else None
          val runningInstances = instances.withFilter(_.isRunning)
          ipPerTaskPortMapping match {
            case Some(portMapping) if portMapping.hostPort.isEmpty =>
              runningInstances.foreach { instance =>
                CompatibilityWith14.tryAppendContainerPort(sb, app, portMapping, instance, delimiter)
              }
            case Some(portMapping) if portMapping.hostPort.nonEmpty =>
              // the task hostPorts only contains an entry for each portMapping that has a hostPort defined
              // We need to compute and use the new index
              CompatibilityWith14.hostPortIndexOffset(app, i).foreach { computedHostPortIndex =>
                runningInstances.foreach { task =>
                  CompatibilityWith14.appendHostPortOrZero(sb, task, computedHostPortIndex, delimiter)
                }
              }
            case _ =>
              runningInstances.foreach { instance =>
                CompatibilityWith14.appendHostPortOrZero(sb, instance, i, delimiter)
              }
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

  private object CompatibilityWith14 {
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
      *
      * Note, at some-point, as a work-around to MARATHON-7407, it was decided that it would be a good idea to output port
      * 0 if no host port for the corresponding service port was found (this would happen in the event that you added a
      * new portMapping). This is rather nonsensical and should be removed when MARATHON-7407 is properly addressed.
      */
    def appendHostPortOrZero(
      sb: StringBuilder, instance: Instance, portIdx: Integer, delimiter: String): Unit = {
      instance.tasksMap.values.withFilter(_.status.condition.isActive).foreach { task =>
        val taskPort = task.status.networkInfo.hostPorts.lift(portIdx).getOrElse(0)
        sb.append(instance.agentInfo.host).append(':').append(taskPort).append(delimiter)
      }
    }
  }

  case class ListTasks(instancesMap: InstancesBySpec, apps: Seq[AppDefinition])
}
