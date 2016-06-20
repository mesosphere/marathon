package mesosphere.marathon.core.task.jobs

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.{ ExpungeOverdueLostTasksActor, OverdueTasksActor }
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskReservationTimeoutHandler, TaskTracker }
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

  def expungeOverdueLostTasks(taskTracker: TaskTracker, stateOpProcessor: TaskStateOpProcessor): Unit = {
    leadershipModule.startWhenLeader(
      ExpungeOverdueLostTasksActor.props(clock, config, taskTracker, stateOpProcessor),
      "expungeOverdueLostTasks"
    )
  }
}
