package mesosphere.marathon.core.task.jobs

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.{ ExpungeOverdueLostTasksActor, OverdueTasksActor }
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ TaskReservationTimeoutHandler, TaskStateOpProcessor, InstanceTracker }
import mesosphere.marathon.MarathonConf

/**
  * This module contains periodically running jobs interacting with the task tracker.
  */
class TaskJobsModule(config: MarathonConf, leadershipModule: LeadershipModule, clock: Clock) {
  def handleOverdueTasks(
    taskTracker: InstanceTracker,
    taskReservationTimeoutHandler: TaskReservationTimeoutHandler,
    killService: KillService): Unit = {
    leadershipModule.startWhenLeader(
      OverdueTasksActor.props(
        config,
        taskTracker,
        taskReservationTimeoutHandler,
        killService,
        clock
      ),
      "killOverdueStagedTasks")
  }

  def expungeOverdueLostTasks(taskTracker: InstanceTracker, stateOpProcessor: TaskStateOpProcessor): Unit = {
    leadershipModule.startWhenLeader(
      ExpungeOverdueLostTasksActor.props(clock, config, taskTracker, stateOpProcessor),
      "expungeOverdueLostTasks"
    )
  }
}
