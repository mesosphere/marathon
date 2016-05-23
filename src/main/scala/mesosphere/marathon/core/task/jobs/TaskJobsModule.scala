package mesosphere.marathon.core.task.jobs

import com.google.inject.Provider
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.{ ExpungeOverdueLostTasksActor, KillOverdueTasksActor }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
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

  def expungeOverdueLostTasks(taskTracker: TaskTracker, updateProcessor: Provider[TaskStatusUpdateProcessor]): Unit = {
    leadershipModule.startWhenLeader(
      ExpungeOverdueLostTasksActor.props(clock, config, taskTracker, updateProcessor),
      "expungeOverdueLostTasks"
    )
  }
}
