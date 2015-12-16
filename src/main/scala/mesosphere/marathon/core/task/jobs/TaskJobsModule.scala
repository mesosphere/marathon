package mesosphere.marathon.core.task.jobs

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.KillOverdueTasksActor
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }

/**
  * This module contains periodically running jobs interacting with the task tracker.
  */
class TaskJobsModule(config: MarathonConf, leadershipModule: LeadershipModule, clock: Clock) {
  def killOverdueTasks(
    taskTracker: TaskTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder): Unit = {
    leadershipModule.startWhenLeader(
      KillOverdueTasksActor.props(config, taskTracker, marathonSchedulerDriverHolder, clock),
      "killOverdueStagedTasks")
  }
}
