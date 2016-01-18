package mesosphere.marathon.core.autoscale

import javax.inject.Provider

import akka.actor.ActorSystem
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.autoscale.impl.{ AutoScaleActor, MesosAgentCountScalePolicy }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.GroupManagement
import mesosphere.util.state.MesosLeaderInfo

class AutoScaleModule(conf: AutoScaleConfig,
                      actorSystem: ActorSystem,
                      leadershipModule: LeadershipModule,
                      groupManagerProvider: Provider[GroupManagement],
                      schedulerProvider: Provider[MarathonSchedulerService],
                      leaderInfo: MesosLeaderInfo,
                      taskTracker: TaskTracker) {

  leadershipModule.startWhenLeader(AutoScaleActor.props(conf, autoScalePolicies,
    groupManagerProvider, schedulerProvider, taskTracker), "AutoScaleActor")

  lazy val autoScalePolicies: Seq[AutoScalePolicy] = Seq(
    new MesosAgentCountScalePolicy(leaderInfo, actorSystem)
  )
}
