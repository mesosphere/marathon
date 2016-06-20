package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedTaskInfo(
    runSpec = AppDefinition(PathId("/thisisignored")),
    inProgress = true,
    tasksLeftToLaunch = 0,
    finalTaskCount = 0,
    tasksLost = 0,
    backOffUntil = Timestamp(0)
  )
}
