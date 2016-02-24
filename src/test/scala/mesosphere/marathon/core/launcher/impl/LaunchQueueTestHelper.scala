package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedTaskInfo(
    app = AppDefinition(PathId("/thisisignored")),
    tasksLeftToLaunch = 0,
    taskLaunchesInFlight = 0,
    tasksLaunched = 0,
    backOffUntil = Timestamp(0)
  )
}
