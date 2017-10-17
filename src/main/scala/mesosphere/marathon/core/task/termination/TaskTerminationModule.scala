package mesosphere.marathon
package core.task.termination

import java.time.Clock

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.termination.impl.{ KillServiceActor, KillServiceDelegate }
import mesosphere.marathon.core.task.tracker.InstanceTrackerModule

class TaskTerminationModule(
    instanceTrackerModule: InstanceTrackerModule,
    leadershipModule: LeadershipModule,
    driverHolder: MarathonSchedulerDriverHolder,
    config: KillConfig,
    clock: Clock) {

  private[this] lazy val stateOpProcessor = instanceTrackerModule.stateOpProcessor

  private[this] lazy val taskKillServiceActorProps: Props =
    KillServiceActor.props(driverHolder, stateOpProcessor, config, clock)

  private[this] lazy val taskKillServiceActor: ActorRef =
    leadershipModule.startWhenLeader(taskKillServiceActorProps, "taskKillServiceActor")

  val taskKillService: KillService = new KillServiceDelegate(taskKillServiceActor)
}
