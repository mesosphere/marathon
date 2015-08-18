package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedTaskCount(
    app = AppDefinition(PathId("/thisisignored")),
    tasksLeftToLaunch = 0,
    taskLaunchesInFlight = 0,
    tasksLaunchedOrRunning = 0,
    backOffUntil = Timestamp(0)
  )
}
