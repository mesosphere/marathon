package mesosphere.marathon.core.jobs

import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.state.AppRepository

/**
  * Provides a [[JobScheduler]] implementation which can be used to launch jobs for a given RunSpec.
  */
class JobModule(
    leadershipModule: LeadershipModule,
    launchQueueModule: LaunchQueueModule,
    appRepo: AppRepository) {

  private[this] val jobSchedulerActorRef = {
    val props = JobSchedulerActor.props(launchQueueModule.launchQueue, appRepo)
    leadershipModule.startWhenLeader(props, "jobScheduler")
  }

  lazy val jobScheduler: JobScheduler = new JobSchedulerDelegate(jobSchedulerActorRef)

}
