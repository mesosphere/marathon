package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl.KillOverdueTasksActor
import mesosphere.marathon.tasks.TaskTracker

/**
  * This module provides some glue between the task tracker, status updates and various components in the application.
  */
class TaskTrackerModule(leadershipModule: LeadershipModule, clock: Clock) {
  def killOverdueTasks(taskTracker: TaskTracker, marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder): Unit = {
    leadershipModule.startWhenLeader(
      KillOverdueTasksActor.props(taskTracker, marathonSchedulerDriverHolder, clock),
      "killOverdueStagedTasks")
  }
}
