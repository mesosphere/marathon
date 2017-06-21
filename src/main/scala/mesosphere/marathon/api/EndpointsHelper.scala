package mesosphere.marathon
package api

import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.AppDefinition

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
  def appsToEndpointString(
    instancesMap: InstancesBySpec,
    apps: Seq[AppDefinition],
    delimiter: String = "\t"): String = {

    val sb = new StringBuilder
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

}
