package mesosphere.marathon.core.launchqueue

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launchqueue.impl.{
  AppTaskLauncherActor,
  LaunchQueueActor,
  LaunchQueueDelegate,
  RateLimiter,
  RateLimiterActor
}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository }
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }

/**
  * Provides a [[LaunchQueue]] implementation which can be used to launch tasks for a given AppDefinition.
  */
class LaunchQueueModule(
    config: LaunchQueueConfig,
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    maybeOfferReviver: Option[OfferReviver],
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskFactory: TaskFactory) {

  private[this] val launchQueueActorRef: ActorRef = {
    val props = LaunchQueueActor.props(config, appActorProps)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }
  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)

  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(
      rateLimiter, appRepository, launchQueueActorRef)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }

  val taskQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActorRef, rateLimiterActor)

  private[this] def appActorProps(app: AppDefinition, count: Int): Props =
    AppTaskLauncherActor.props(
      config,
      subOfferMatcherManager,
      clock,
      taskFactory,
      maybeOfferReviver,
      taskTracker,
      rateLimiterActor)(app, count)
}
