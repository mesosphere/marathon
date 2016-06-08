package mesosphere.marathon.core.launchqueue

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launcher.TaskOpFactory
import mesosphere.marathon.core.launchqueue.impl.{
  TaskLauncherActor,
  LaunchQueueActor,
  LaunchQueueDelegate,
  RateLimiter,
  RateLimiterActor
}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.RunSpec

/**
  * Provides a [[LaunchQueue]] implementation which can be used to launch tasks for a given RunSpec.
  */
class LaunchQueueModule(
    config: LaunchQueueConfig,
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    maybeOfferReviver: Option[OfferReviver],
    taskTracker: TaskTracker,
    taskOpFactory: TaskOpFactory) {

  private[this] val launchQueueActorRef: ActorRef = {
    val props = LaunchQueueActor.props(config, runSpecActorProps)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }
  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)

  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(
      rateLimiter, launchQueueActorRef)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }

  val launchQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActorRef, rateLimiterActor)

  private[this] def runSpecActorProps(runSpec: RunSpec, count: Int): Props =
    TaskLauncherActor.props(
      config,
      subOfferMatcherManager,
      clock,
      taskOpFactory,
      maybeOfferReviver,
      taskTracker,
      rateLimiterActor)(runSpec, count)
}
