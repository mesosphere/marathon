package mesosphere.marathon
package core.task.jobs

import java.time.Clock

import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.{ExpungeOverdueLostTasksActor, OverdueInstancesActor}
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker

/**
  * This module contains periodically running jobs interacting with the task tracker.
  */
class TaskJobsModule(config: MarathonConf, leadershipModule: LeadershipModule, clock: Clock) {
  def handleOverdueTasks(
    instanceTracker: InstanceTracker,
    killService: KillService): Unit = {
    leadershipModule.startWhenLeader(
      OverdueInstancesActor.props(
        config,
        instanceTracker,
        killService,
        clock
      ),
      "killOverdueStagedTasks")
  }

  def expungeOverdueLostTasks(instanceTracker: InstanceTracker): Unit = {
    leadershipModule.startWhenLeader(
      ExpungeOverdueLostTasksActor.props(clock, config, instanceTracker),
      "expungeOverdueLostTasks"
    )
  }
}
