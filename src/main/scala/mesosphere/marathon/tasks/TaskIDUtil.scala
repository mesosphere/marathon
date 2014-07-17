package mesosphere.marathon.tasks

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskID

/**
  * Utility functions for dealing with TaskIDs
  */

object TaskIDUtil {

  val taskDelimiter = "."
  val TaskId = """^(.+)\.(.+)$""".r
  val OldTaskId = """^(.+)[_](.+)$""".r
  val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  def taskId(appId: PathId): String = {
    appId.safePath + taskDelimiter + uuidGenerator.generate()
  }

  def appID(taskId: TaskID): PathId = {
    taskId.getValue match {
      //new task ids contain . as delimiter but _ for the safe path
      case TaskId(appId, uuid)    => PathId.fromSafePath(appId)
      //version 0.5 and below use _ as delimiter
      case OldTaskId(appId, uuid) => PathId.fromSafePath(appId)
    }
  }
}
