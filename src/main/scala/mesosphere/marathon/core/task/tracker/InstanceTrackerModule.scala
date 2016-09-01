package mesosphere.marathon.core.task.tracker

import akka.actor.ActorRef
import akka.stream.Materializer
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.TaskRepository

/**
  * Provides the interfaces to query the current task state ([[InstanceTracker]]) and to
  * update the task state ([[TaskStateOpProcessor]]).
  */
class InstanceTrackerModule(
    clock: Clock,
    metrics: Metrics,
    config: InstanceTrackerConfig,
    leadershipModule: LeadershipModule,
    taskRepository: TaskRepository,
    updateSteps: Seq[TaskUpdateStep])(implicit mat: Materializer) {
  lazy val taskTracker: InstanceTracker = new InstanceTrackerDelegate(Some(metrics), config, taskTrackerActorRef)
  lazy val taskTrackerUpdateStepProcessor: InstanceTrackerUpdateStepProcessor =
    new InstanceTrackerUpdateStepProcessorImpl(updateSteps, metrics)

  def taskCreationHandler: TaskCreationHandler = taskStateOpProcessor
  def stateOpProcessor: TaskStateOpProcessor = taskStateOpProcessor
  def taskReservationTimeoutHandler: TaskReservationTimeoutHandler = taskStateOpProcessor

  private[this] def stateOpResolver(taskTrackerRef: ActorRef): InstanceOpProcessorImpl.TaskStateOpResolver =
    new InstanceOpProcessorImpl.TaskStateOpResolver(new InstanceTrackerDelegate(None, config, taskTrackerRef))
  private[this] def taskOpProcessor(taskTrackerRef: ActorRef): InstanceOpProcessor =
    new InstanceOpProcessorImpl(taskTrackerRef, taskRepository, stateOpResolver(taskTrackerRef), config)
  private[this] lazy val taskUpdaterActorMetrics = new InstanceUpdateActor.ActorMetrics(metrics)
  private[this] def taskUpdaterActorProps(taskTrackerRef: ActorRef) =
    InstanceUpdateActor.props(clock, taskUpdaterActorMetrics, taskOpProcessor(taskTrackerRef))
  private[this] lazy val taskLoader = new InstancesLoaderImpl(taskRepository)
  private[this] lazy val taskTrackerMetrics = new InstanceTrackerActor.ActorMetrics(metrics)
  private[this] lazy val taskTrackerActorProps =
    InstanceTrackerActor.props(taskTrackerMetrics, taskLoader, taskTrackerUpdateStepProcessor, taskUpdaterActorProps)
  protected lazy val taskTrackerActorName = "taskTracker"
  private[this] lazy val taskTrackerActorRef = leadershipModule.startWhenLeader(
    taskTrackerActorProps, taskTrackerActorName
  )
  private[this] lazy val taskStateOpProcessor =
    new TaskCreationHandlerAndUpdaterDelegate(clock, config, taskTrackerActorRef)
}
