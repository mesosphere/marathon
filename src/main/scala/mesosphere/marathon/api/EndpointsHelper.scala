package mesosphere.marathon.api

import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.AppDefinition

object EndpointsHelper {
  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.  The data columns in the result are separated by
    * the supplied delimiter string.
    */
  def appsToEndpointString(
    taskTracker: TaskTracker,
    apps: Seq[AppDefinition],
    delimiter: String): String = {
    val tasksMap = taskTracker.tasksByAppSync

    val sb = new StringBuilder
    for (app <- apps if app.ipAddress.isEmpty) {
      val tasks = tasksMap.appTasks(app.id)
      val cleanId = app.id.safePath

      val servicePorts = app.servicePorts

      if (servicePorts.isEmpty) {
        sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
        for (task <- tasks if task.isRunning) {
          sb.append(task.agentInfo.host).append(' ')
        }
        sb.append('\n')
      } else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(cleanId).append(delimiter).append(port).append(delimiter)

          for (task <- tasks if task.isRunning) {
            val taskPort = task.launched.flatMap(_.hostPorts.drop(i).headOption).getOrElse(0)
            sb.append(task.agentInfo.host).append(':').append(taskPort).append(delimiter)
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

}
