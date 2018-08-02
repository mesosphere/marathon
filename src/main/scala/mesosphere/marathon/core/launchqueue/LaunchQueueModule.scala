package mesosphere.marathon
package core.launchqueue

import java.time.Clock

import akka.actor.{ActorRef, Props}
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launchqueue.impl._
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{Region, RunSpec}

/**
  * Provides a [[LaunchQueue]] implementation which can be used to launch tasks for a given RunSpec.
  */
class LaunchQueueModule(
    config: LaunchQueueConfig,
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    maybeOfferReviver: Option[OfferReviver],
    instanceTracker: InstanceTracker,
    taskOpFactory: InstanceOpFactory,
    groupManager: GroupManager,
    localRegion: () => Option[Region]) {

  private[this] val offerMatchStatisticsActor: ActorRef = {
    leadershipModule.startWhenLeader(OfferMatchStatisticsActor.props(), "offerMatcherStatistics")
  }

  private[this] val launchQueueActor: ActorRef = {
    def runSpecActorProps(runSpec: RunSpec): Props =
      TaskLauncherActor.props(
        config,
        subOfferMatcherManager,
        clock,
        taskOpFactory,
        maybeOfferReviver,
        instanceTracker,
        rateLimiterActor,
        offerMatchStatisticsActor,
        localRegion)(runSpec)
    val props = LaunchQueueActor.props(config, offerMatchStatisticsActor, instanceTracker, groupManager, runSpecActorProps)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }

  val rateLimiter: RateLimiter = new RateLimiter(clock)
  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(
      rateLimiter, launchQueueActor)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }
  val launchQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActor, rateLimiterActor)
}
