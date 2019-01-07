package mesosphere.marathon
package core.task.termination

import java.time.Clock

import akka.actor.{ActorRef, ActorSystem, Props}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.termination.impl.{KillServiceActor, KillServiceDelegate}
import mesosphere.marathon.core.task.tracker.InstanceTrackerModule
import mesosphere.marathon.metrics.Metrics

class TaskTerminationModule(
    instanceTrackerModule: InstanceTrackerModule,
    leadershipModule: LeadershipModule,
    driverHolder: MarathonSchedulerDriverHolder,
    config: KillConfig,
    metrics: Metrics,
    clock: Clock,
    actorSystem: ActorSystem) {

  private[this] lazy val instanceTracker = instanceTrackerModule.instanceTracker

  private[this] lazy val taskKillServiceActorProps: Props =
    KillServiceActor.props(driverHolder, instanceTracker, config, metrics, clock)

  private[this] lazy val taskKillServiceActor: ActorRef =
    leadershipModule.startWhenLeader(taskKillServiceActorProps, "taskKillServiceActor")

  val taskKillService: KillService = new KillServiceDelegate(taskKillServiceActor, actorSystem.eventStream)
}
