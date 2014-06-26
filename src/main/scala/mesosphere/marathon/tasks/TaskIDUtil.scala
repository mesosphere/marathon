package mesosphere.marathon.tasks

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskID

/**
  * Utility functions for dealing with TaskIDs
  */

object TaskIDUtil {

  val taskDelimiter = "."
  val uuidGenerator =
    Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  def taskId(appId: PathId): String = {
    appId.safePath + taskDelimiter + uuidGenerator.generate()
  }

  def appID(taskId: TaskID): PathId = {
    val taskIdString = taskId.getValue
    val appIdString = taskIdString.substring(0, taskIdString.lastIndexOf(taskDelimiter))
    PathId.fromSafePath(appIdString)
  }
}
