package mesosphere.marathon.api

import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.AppDefinition

object EndpointsHelper {
  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.  The data columns in the result are separated by
    * the supplied delimiter string.
    */
  def appsToEndpointString(
    instanceTracker: InstanceTracker,
    apps: Seq[AppDefinition],
    delimiter: String): String = {
    val instancesMap = instanceTracker.instancesBySpecSync

    val sb = new StringBuilder
    for (app <- apps if app.ipAddress.isEmpty) {
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

          for {
            instance <- instances if instance.isRunning
            (_, task) <- instance.tasksMap
          } {
            val taskPort = task.status.networkInfo.hostPorts.drop(i).headOption.getOrElse(0)
            sb.append(instance.agentInfo.host).append(':').append(taskPort).append(delimiter)
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

}
