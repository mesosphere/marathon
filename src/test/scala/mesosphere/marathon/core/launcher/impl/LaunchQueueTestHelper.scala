package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedTaskInfo(
    app = AppDefinition(PathId("/thisisignored")),
    inProgress = true,
    tasksLeftToLaunch = 0,
    finalTaskCount = 0,
    backOffUntil = Timestamp(0)
  )
}
