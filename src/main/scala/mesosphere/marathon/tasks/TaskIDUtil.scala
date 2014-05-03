package mesosphere.marathon.tasks

import org.apache.mesos.Protos.TaskID

/**
 * Utility functions for dealing with TaskIDs
 *
 * @author Tobi Knaup
 */

object TaskIDUtil {

  val taskDelimiter = "_"

  def taskId(appName: String, sequence: Int) = {
    "%s%s%d-%d".format(appName, taskDelimiter, sequence, System.currentTimeMillis())
  }

  def appID(taskId: TaskID) = {
    val taskIdString = taskId.getValue
    taskIdString.substring(0, taskIdString.lastIndexOf(taskDelimiter))
  }
}
