package mesosphere.marathon

import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.api.v1.AppDefinition

/**
 * Utility functions for dealing with TaskIDs
 *
 * @author Tobi Knaup
 */

object TaskIDUtil {

  def taskId(appName: String, sequence: Int) = {
    "%s-%d".format(appName, sequence)
  }

  def appName(taskId: TaskID) = {
    val taskIdString = taskId.getValue
    taskIdString.substring(0, taskIdString.indexOf("-"))
  }

}