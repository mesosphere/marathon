package mesosphere.marathon.core.task.jobs

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.OverdueTasksActor
import mesosphere.marathon.core.task.tracker.{ TaskReservationTimeoutHandler, TaskTracker }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }

/**
  * This module contains periodically running jobs interacting with the task tracker.
  */
class TaskJobsModule(config: MarathonConf, leadershipModule: LeadershipModule, clock: Clock) {
  def handleOverdueTasks(
    taskTracker: TaskTracker,
    taskReservationTimeoutHandler: TaskReservationTimeoutHandler,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder): Unit = {
    leadershipModule.startWhenLeader(
      OverdueTasksActor.props(
        config,
        taskTracker,
        taskReservationTimeoutHandler,
        marathonSchedulerDriverHolder,
        clock
      ),
      "killOverdueStagedTasks")
  }
}
