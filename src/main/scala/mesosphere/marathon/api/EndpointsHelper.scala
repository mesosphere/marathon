package mesosphere.marathon.api

import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import org.apache.mesos.Protos.TaskState
import scala.collection.JavaConverters._

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
    val sb = new StringBuilder
    for (app <- apps) {
      val cleanId = app.id.safePath.replaceAll("\\s+", "_")
      val tasks = taskTracker.get(app.id)

      val servicePorts = app.servicePorts()

      if (servicePorts.isEmpty) {
        sb.append(s"${cleanId}$delimiter $delimiter")
        for (task <- tasks if task.getStatus.getState == TaskState.TASK_RUNNING) {
          sb.append(s"${task.getHost} ")
        }
        sb.append(s"\n")
      }
      else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(s"$cleanId$delimiter$port$delimiter")
          for (task <- tasks if task.getStatus.getState == TaskState.TASK_RUNNING) {
            val ports = task.getPortsList.asScala.lift
            sb.append(s"${task.getHost}:${ports(i).getOrElse(0)}$delimiter")
          }
          sb.append("\n")
        }
      }
    }
    sb.toString()
  }

}
