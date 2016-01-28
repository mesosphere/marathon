package mesosphere.marathon.core.autoscale

import javax.inject.Provider

import akka.actor.ActorSystem
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.autoscale.impl.{ AutoScaleActor, AutoScaleAppActor, MesosAgentCountScalePolicy }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.GroupManager
import mesosphere.util.state.MesosLeaderInfo

class AutoScaleModule(conf: AutoScaleConfig,
                      actorSystem: ActorSystem,
                      leadershipModule: LeadershipModule,
                      groupManager: Provider[GroupManager],
                      scheduler: Provider[MarathonSchedulerService],
                      leaderInfo: MesosLeaderInfo,
                      taskTracker: TaskTracker,
                      clock: Clock) {

  leadershipModule.startWhenLeader(
    AutoScaleActor.props(conf, groupManager, scheduler,
      AutoScaleAppActor.props(_, _, _, groupManager, scheduler, autoScalePolicies, taskTracker, clock, conf)),
    "AutoScaleActor")

  lazy val autoScalePolicies: Seq[AutoScalePolicy] = Seq(
    new MesosAgentCountScalePolicy(leaderInfo, actorSystem)
  )
}
