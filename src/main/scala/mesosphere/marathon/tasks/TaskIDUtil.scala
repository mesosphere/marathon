package mesosphere.marathon.tasks

import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskID
import com.fasterxml.uuid.{ EthernetAddress, Generators }
import PathId._

/**
  * Utility functions for dealing with TaskIDs
  */

object TaskIDUtil {

  val taskDelimiter = "."
  val uuidGenerator =
    Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  def taskId(appId: PathId): String = {
    appId.toString + taskDelimiter + uuidGenerator.generate()
  }

  def appID(taskId: TaskID): PathId = {
    val taskIdString = taskId.getValue
    taskIdString.substring(0, taskIdString.lastIndexOf(taskDelimiter)).toPath
  }
}
