package mesosphere.marathon.tasks

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskID
import scala.util.Try

/**
  * Utility functions for dealing with TaskIDs
  */

class TaskIdUtil {
  val taskDelimiter = "."
  val TaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r
  val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  def taskId(appId: PathId): String = {
    appId.safePath + taskDelimiter + uuidGenerator.generate()
  }

  def appId(taskId: TaskID): Try[PathId] = appId(taskId.getValue)

  def appId(taskId: String): Try[PathId] = {
    Try(taskId match {
      case TaskIdRegex(appId, uuid) => PathId.fromSafePath(appId)
    })
  }

  def newTaskId(appId: PathId): TaskID = {
    TaskID.newBuilder()
      .setValue(taskId(appId))
      .build
  }
}

object TaskIdUtil extends TaskIdUtil
