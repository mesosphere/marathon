package mesosphere.marathon
package core.task.jobs

import java.time.Clock

import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.jobs.impl.{ ExpungeOverdueLostTasksActor, OverdueTasksActor }
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, InstanceTracker }
import mesosphere.marathon.MarathonConf

/**
  * This module contains periodically running jobs interacting with the task tracker.
  */
class TaskJobsModule(config: MarathonConf, leadershipModule: LeadershipModule, clock: Clock) {
  def handleOverdueTasks(
    taskTracker: InstanceTracker,
    taskStateOpProcessor: TaskStateOpProcessor,
    killService: KillService): Unit = {
    leadershipModule.startWhenLeader(
      OverdueTasksActor.props(
        config,
        taskTracker,
        taskStateOpProcessor,
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
