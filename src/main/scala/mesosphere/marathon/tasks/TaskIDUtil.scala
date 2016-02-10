package mesosphere.marathon.tasks

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskID

/**
  * Utility functions for dealing with TaskIDs
  */
// FIXME(PK): Remove, merge into Task.Id
class TaskIdUtil {
  val appDelimiter = "."
  val TaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r
  val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  // FIXME(PK): Remove, see task.appId
  def appId(taskId: TaskID): PathId = appId(taskId.getValue)

  // this should actually be a try or option
  // FIXME(PK): Remove, see task.appId
  def appId(taskId: String): PathId = {
    taskId match {
      case TaskIdRegex(appId, uuid) => PathId.fromSafePath(appId)
      case _                        => throw new MatchError(s"taskId $taskId is no valid identifier")
    }
  }

  // FIXME(PK): remove, merge with Task.Id.random
  def newTaskId(appId: PathId): TaskID = {
    val taskId = appId.safePath + appDelimiter + uuidGenerator.generate()
    TaskID.newBuilder()
      .setValue(taskId)
      .build
  }
}

object TaskIdUtil extends TaskIdUtil
