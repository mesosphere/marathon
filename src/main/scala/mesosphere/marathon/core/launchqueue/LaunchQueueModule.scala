package mesosphere.marathon
package core.launchqueue

import akka.NotUsed
import akka.stream.scaladsl.{BroadcastHub, Keep}
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Source}
import java.time.Clock

import akka.actor.{ActorRef, Props}
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launchqueue.impl._
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{Region, RunSpec}
import scala.concurrent.ExecutionContext

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
    localRegion: () => Option[Region])(implicit materializer: Materializer, ec: ExecutionContext) {

  val (offerMatchStatisticsInput, offerMatchStatistics) =
    Source.queue[OfferMatchStatistics.OfferMatchUpdate](Int.MaxValue, OverflowStrategy.fail).
      toMat(BroadcastHub.sink)(Keep.both).
      run

  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)
  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(rateLimiter)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }
  val rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed] =
    Source.actorRef[RateLimiter.DelayUpdate](Int.MaxValue, OverflowStrategy.fail)
      .watchTermination()(Keep.both)
      .mapMaterializedValue {
        case (ref, termination) =>
          rateLimiterActor.tell(RateLimiterActor.Subscribe, ref)
          termination.onComplete { _ =>
            rateLimiterActor.tell(RateLimiterActor.Unsubscribe, ref)
          }(ExecutionContexts.callerThread)
          NotUsed
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
        offerMatchStatisticsInput,
        localRegion)(runSpec.id)
    val props = LaunchQueueActor.props(config, instanceTracker, groupManager, runSpecActorProps, rateLimiterUpdates)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }

  val launchQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActor, rateLimiterActor)

  val launchStats = LaunchStats(
    groupManager,
    clock,
    instanceTracker.instanceUpdates,
    rateLimiterUpdates,
    offerMatchStatistics)
}
