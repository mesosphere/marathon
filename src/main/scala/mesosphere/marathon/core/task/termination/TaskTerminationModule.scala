package mesosphere.marathon
package core.task.termination

import java.time.Clock

import akka.actor.{ActorRef, Props}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.termination.impl.{KillServiceActor, KillServiceDelegate}
import mesosphere.marathon.core.task.tracker.SchedulerModule
import mesosphere.marathon.metrics.Metrics

class TaskTerminationModule(
    instanceTrackerModule: SchedulerModule,
    leadershipModule: LeadershipModule,
    driverHolder: MarathonSchedulerDriverHolder,
    config: KillConfig,
    metrics: Metrics,
    clock: Clock) {

  private[this] lazy val instanceTracker = instanceTrackerModule.instanceTracker

  private[this] lazy val taskKillServiceActorProps: Props =
    KillServiceActor.props(driverHolder, instanceTracker, config, metrics, clock)

  private[this] lazy val taskKillServiceActor: ActorRef =
    leadershipModule.startWhenLeader(taskKillServiceActorProps, "taskKillServiceActor")

  val taskKillService: KillService = new KillServiceDelegate(taskKillServiceActor)
}
