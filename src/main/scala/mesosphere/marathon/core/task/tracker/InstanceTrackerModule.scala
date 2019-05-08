package mesosphere.marathon
package core.task.tracker

import java.time.Clock

import akka.stream.Materializer
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.core.instance.update.{InstanceChangeHandler, InstanceUpdateOpResolver}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.{GroupRepository, InstanceRepository, InstanceView}

/**
  * Provides the interfaces to query or update the current instance state ([[InstanceTracker]]).
  */
class InstanceTrackerModule(
    metrics: Metrics,
    clock: Clock,
    config: InstanceTrackerConfig,
    leadershipModule: LeadershipModule,
    instanceRepository: InstanceRepository,
    groupRepository: GroupRepository,
    updateSteps: Seq[InstanceChangeHandler],
    crashStrategy: CrashStrategy)(implicit mat: Materializer) {
  lazy val instanceTracker: InstanceTracker =
    new InstanceTrackerDelegate(metrics, clock, config, instanceTrackerActorRef)
  lazy val instanceTrackerUpdateStepProcessor: InstanceTrackerUpdateStepProcessor =
    new InstanceTrackerUpdateStepProcessorImpl(metrics, updateSteps)

  private[this] lazy val updateOpResolver: InstanceUpdateOpResolver = new InstanceUpdateOpResolver(clock)
  private[this] lazy val instancesLoader = new InstancesLoaderImpl(InstanceView(instanceRepository, groupRepository), config)
  private[this] lazy val instanceTrackerMetrics = new InstanceTrackerActor.ActorMetrics(metrics)
  private[this] lazy val instanceTrackerActorProps = InstanceTrackerActor.props(
    instanceTrackerMetrics, instancesLoader, instanceTrackerUpdateStepProcessor, updateOpResolver, InstanceView(instanceRepository, groupRepository), clock, crashStrategy)
  protected lazy val instanceTrackerActorName = "instanceTracker"
  private[this] lazy val instanceTrackerActorRef = leadershipModule.startWhenLeader(
    instanceTrackerActorProps, instanceTrackerActorName
  )
}
