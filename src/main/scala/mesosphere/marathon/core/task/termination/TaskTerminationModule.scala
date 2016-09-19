package mesosphere.marathon.core.task.termination

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.termination.impl.{ TaskKillServiceActor, TaskKillServiceDelegate }
import mesosphere.marathon.core.task.tracker.TaskTrackerModule

class TaskTerminationModule(
    taskTrackerModule: TaskTrackerModule,
    leadershipModule: LeadershipModule,
    driverHolder: MarathonSchedulerDriverHolder,
    config: TaskKillConfig,
    clock: Clock) {

  private[this] lazy val stateOpProcessor = taskTrackerModule.stateOpProcessor

  private[this] lazy val taskKillServiceActorProps: Props =
    TaskKillServiceActor.props(driverHolder, stateOpProcessor, config, clock)

  private[this] lazy val taskKillServiceActor: ActorRef =
    leadershipModule.startWhenLeader(taskKillServiceActorProps, "taskKillServiceActor")

  val taskKillService: TaskKillService = new TaskKillServiceDelegate(taskKillServiceActor)
}
