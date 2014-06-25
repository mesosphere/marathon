package mesosphere.marathon.tasks

import org.apache.mesos.Protos.TaskID
import com.fasterxml.uuid.{ EthernetAddress, Generators }

/**
  * Utility functions for dealing with TaskIDs
  */

object TaskIDUtil {

  val taskDelimiter = "."
  val uuidGenerator =
    Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  def taskId(appName: String) = {
    appName + taskDelimiter + uuidGenerator.generate()
  }

  def appID(taskId: TaskID) = {
    val taskIdString = taskId.getValue
    taskIdString.substring(0, taskIdString.lastIndexOf(taskDelimiter))
  }
}
