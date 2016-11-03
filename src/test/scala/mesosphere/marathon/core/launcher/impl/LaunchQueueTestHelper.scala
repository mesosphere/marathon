package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedInstanceInfo(
    runSpec = AppDefinition(PathId("/thisisignored")),
    inProgress = true,
    instancesLeftToLaunch = 0,
    finalInstanceCount = 0,
    unreachableInstances = 0,
    backOffUntil = Timestamp(0),
    startedAt = Timestamp(0)
  )
}
