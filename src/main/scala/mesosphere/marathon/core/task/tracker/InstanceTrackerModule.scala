package mesosphere.marathon
package core.task.tracker

import java.time.Clock

import akka.actor.ActorRef
import akka.stream.Materializer
import mesosphere.marathon.core.instance.update.{InstanceChangeHandler, InstanceUpdateOpResolver}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.duration._

/**
  * Provides the interfaces to query or update the current instance state ([[InstanceTracker]]).
  */
class InstanceTrackerModule(
    clock: Clock,
    config: InstanceTrackerConfig,
    leadershipModule: LeadershipModule,
    instanceRepository: InstanceRepository,
    updateSteps: Seq[InstanceChangeHandler])(implicit mat: Materializer) {
  lazy val instanceTracker: InstanceTracker =
    new InstanceTrackerDelegate(clock, config, instanceTrackerActorRef)
  lazy val instanceTrackerUpdateStepProcessor: InstanceTrackerUpdateStepProcessor =
    new InstanceTrackerUpdateStepProcessorImpl(updateSteps)

  private[this] def updateOpResolver(instanceTrackerRef: ActorRef): InstanceUpdateOpResolver =
    new InstanceUpdateOpResolver(
      new InstanceTrackerDelegate(clock, config, instanceTrackerRef), clock)
  private[this] lazy val instanceUpdaterActorMetrics = new InstanceUpdateActor.ActorMetrics()
  private[this] def instanceUpdaterActorProps(instanceTrackerRef: ActorRef) =
    InstanceUpdateActor.props(clock, instanceUpdaterActorMetrics, instanceTrackerRef, updateOpResolver(instanceTrackerRef), Duration(config.internalTaskTrackerRequestTimeout(), MILLISECONDS))
  private[this] lazy val instancesLoader = new InstancesLoaderImpl(instanceRepository)
  private[this] lazy val instanceTrackerMetrics = new InstanceTrackerActor.ActorMetrics()
  private[this] lazy val instanceTrackerActorProps = InstanceTrackerActor.props(
    instanceTrackerMetrics, instancesLoader, instanceTrackerUpdateStepProcessor, instanceUpdaterActorProps, instanceRepository)
  protected lazy val instanceTrackerActorName = "instanceTracker"
  private[this] lazy val instanceTrackerActorRef = leadershipModule.startWhenLeader(
    instanceTrackerActorProps, instanceTrackerActorName
  )
}
