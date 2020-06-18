package mesosphere.marathon
package core.launchqueue

import akka.NotUsed
import akka.stream.scaladsl.BroadcastHub
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Source}
import java.time.Clock

import akka.actor.{ActorRef, Props}
import akka.event.EventStream
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launchqueue.impl._
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{Region, RunSpec}
import org.apache.mesos.Protos.FrameworkInfo

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Provides a [[LaunchQueue]] implementation which can be used to launch tasks for a given RunSpec.
  */
class LaunchQueueModule(
    metrics: Metrics,
    config: LaunchQueueConfig,
    reviveConfig: ReviveOffersConfig,
    eventStream: EventStream,
    driverHolder: MarathonSchedulerDriverHolder,
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    instanceTracker: InstanceTracker,
    taskOpFactory: InstanceOpFactory,
    runSpecProvider: GroupManager.RunSpecProvider,
    localRegion: () => Option[Region],
    initialFrameworkInfo: Future[FrameworkInfo]
)(implicit materializer: Materializer, ec: ExecutionContext) {

  val (offerMatchStatisticsInput, offerMatchStatistics) =
    Source.queue[OfferMatchStatistics.OfferMatchUpdate](Int.MaxValue, OverflowStrategy.fail).toMat(BroadcastHub.sink)(Keep.both).run

  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)
  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(rateLimiter)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }
  val rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed] =
    Source
      .actorRef[RateLimiter.DelayUpdate](Int.MaxValue, OverflowStrategy.fail)
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
        instanceTracker,
        rateLimiterActor,
        offerMatchStatisticsInput,
        localRegion
      )(runSpec.id)
    val props = LaunchQueueActor.props(config, instanceTracker, runSpecActorProps, rateLimiterUpdates)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }

  val launchQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActor, rateLimiterActor)

  val launchStats = LaunchStats(runSpecProvider, clock, instanceTracker.instanceUpdates, rateLimiterUpdates, offerMatchStatistics)

  def reviveOffersActor(): ActorRef = {
    val props = ReviveOffersActor.props(
      metrics,
      initialFrameworkInfo,
      reviveConfig.mesosRole(),
      reviveConfig.minReviveOffersInterval().millis,
      instanceTracker.instanceUpdates,
      rateLimiterUpdates,
      driverHolder,
      reviveConfig.suppressOffers()
    )
    leadershipModule.startWhenLeader(props, "reviveOffers")
  }
}
