package mesosphere.marathon
package core.task.tracker

import java.time.Clock

import akka.actor.ActorRef
import akka.stream.Materializer
import mesosphere.marathon.core.instance.update.{ InstanceChangeHandler, InstanceUpdateOpResolver }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.storage.repository.InstanceRepository

/**
  * Provides the interfaces to query the current task state ([[InstanceTracker]]) and to
  * update the task state ([[TaskStateOpProcessor]]).
  */
class InstanceTrackerModule(
    clock: Clock,
    config: InstanceTrackerConfig,
    leadershipModule: LeadershipModule,
    instanceRepository: InstanceRepository,
    updateSteps: Seq[InstanceChangeHandler])(implicit mat: Materializer) {
  lazy val instanceTracker: InstanceTracker =
    new InstanceTrackerDelegate(config, instanceTrackerActorRef)
  lazy val instanceTrackerUpdateStepProcessor: InstanceTrackerUpdateStepProcessor =
    new InstanceTrackerUpdateStepProcessorImpl(updateSteps)

  def instanceCreationHandler: InstanceCreationHandler = instanceStateOpProcessor
  def stateOpProcessor: TaskStateOpProcessor = instanceStateOpProcessor

  private[this] def updateOpResolver(instanceTrackerRef: ActorRef): InstanceUpdateOpResolver =
    new InstanceUpdateOpResolver(
      new InstanceTrackerDelegate(config, instanceTrackerRef), clock)
  private[this] def instanceOpProcessor(instanceTrackerRef: ActorRef): InstanceOpProcessor =
    new InstanceOpProcessorImpl(instanceTrackerRef, instanceRepository, updateOpResolver(instanceTrackerRef), config)
  private[this] lazy val instanceUpdaterActorMetrics = new InstanceUpdateActor.ActorMetrics()
  private[this] def instanceUpdaterActorProps(instanceTrackerRef: ActorRef) =
    InstanceUpdateActor.props(clock, instanceUpdaterActorMetrics, instanceOpProcessor(instanceTrackerRef))
  private[this] lazy val instancesLoader = new InstancesLoaderImpl(instanceRepository)
  private[this] lazy val instanceTrackerMetrics = new InstanceTrackerActor.ActorMetrics()
  private[this] lazy val instanceTrackerActorProps = InstanceTrackerActor.props(
    instanceTrackerMetrics, instancesLoader, instanceTrackerUpdateStepProcessor, instanceUpdaterActorProps)
  protected lazy val instanceTrackerActorName = "instanceTracker"
  private[this] lazy val instanceTrackerActorRef = leadershipModule.startWhenLeader(
    instanceTrackerActorProps, instanceTrackerActorName
  )
  private[this] lazy val instanceStateOpProcessor =
    new InstanceCreationHandlerAndUpdaterDelegate(clock, config, instanceTrackerActorRef)
}
