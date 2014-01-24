package mesosphere.marathon.api

import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v1.AppDefinition

object EndpointsHelper {

  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.  The data columns in the result are separated by
    * the supplied delimiter string.
    */
  def appsToEndpointString(
    taskTracker: TaskTracker,
    apps: Seq[AppDefinition],
    delimiter: String
  ): String = {
    val sb = new StringBuilder
    for (app <- apps) {
      val cleanId = app.id.replaceAll("\\s+", "_")
      val tasks = taskTracker.get(app.id)
      for ((port, i) <- app.ports.zipWithIndex) {
        sb.append(s"$cleanId$delimiter$port$delimiter")
        for (task <- tasks) {
          sb.append(s"${task.getHost}:${task.getPorts(i)}$delimiter")
        }
        sb.append("\n")
      }
    }
    sb.toString()
  }

}