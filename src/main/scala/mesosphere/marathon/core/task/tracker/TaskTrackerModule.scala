package mesosphere.marathon.core.task.tracker

import akka.actor.ActorRef
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.bus.TaskStatusObservables
import mesosphere.marathon.core.task.tracker.impl.{ TaskStatusUpdateActor, KillOverdueTasksActor }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }

/**
  * This module provides some glue between the task tracker, status updates and various components in the application.
  */
class TaskTrackerModule(leadershipModule: LeadershipModule, clock: Clock) {
  def killOverdueTasks(taskTracker: TaskTracker, marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder): Unit = {
    leadershipModule.startWhenLeader(
      KillOverdueTasksActor.props(taskTracker, marathonSchedulerDriverHolder, clock),
      "killOverdueStagedTasks")
  }

  def processTaskStatusUpdates(
    taskStatusObservable: TaskStatusObservables,
    eventBus: EventStream,
    schedulerActor: ActorRef,
    taskIdUtil: TaskIdUtil,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder): ActorRef = {

    val props = TaskStatusUpdateActor.props(
      taskStatusObservable, eventBus, schedulerActor, taskIdUtil, healthCheckManager, taskTracker,
      marathonSchedulerDriverHolder
    )
    leadershipModule.startWhenLeader(props, "taskStatusUpdate", considerPreparedOnStart = false)
  }
}
