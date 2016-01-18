package mesosphere.marathon.api

import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.Protos.TaskState

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
    val tasksMap = taskTracker.list

    val sb = new StringBuilder
    for (app <- apps if app.ipAddress.isEmpty) {
      val tasks = tasksMap.getTasks(app.id)
      val cleanId = app.id.safePath

      val servicePorts = app.servicePorts

      if (servicePorts.isEmpty) {
        sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
        for (task <- tasks if task.getStatus.getState == TaskState.TASK_RUNNING) {
          sb.append(task.getHost).append(' ')
        }
        sb.append('\n')
      }
      else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(cleanId).append(delimiter).append(port).append(delimiter)

          for (task <- tasks if task.getStatus.getState == TaskState.TASK_RUNNING) {
            val taskPort = Option(task.getPortsList.get(i): java.lang.Integer).getOrElse(java.lang.Integer.valueOf(0))
            sb.append(task.getHost).append(':').append(taskPort).append(delimiter)
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

}
