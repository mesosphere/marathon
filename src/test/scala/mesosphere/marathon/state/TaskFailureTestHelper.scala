package mesosphere.marathon.state

import org.apache.mesos.{ Protos => mesos }

object TaskFailureTestHelper {
  lazy val taskFailure = TaskFailure(
    appId = PathId("/group/app"),
    taskId = mesos.TaskID.newBuilder.setValue("group_app-12345").build,
    state = mesos.TaskState.TASK_FAILED,
    message = "Process exited with status [1]",
    host = "slave5.mega.co",
    version = Timestamp(1000),
    timestamp = Timestamp(2000)
  )
}
