package mesosphere.marathon.core.task.tracker

import akka.actor.ActorRef
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.TaskRepository

/**
  * Provides the interfaces to query the current task state ([[TaskTracker]]) and to
  * update the task state ([[TaskUpdater]], [[TaskCreationHandler]]).
  */
class TaskTrackerModule(
    clock: Clock,
    metrics: Metrics,
    config: TaskTrackerConfig,
    leadershipModule: LeadershipModule,
    taskRepository: TaskRepository,
    updateSteps: Seq[TaskStatusUpdateStep]) {
  lazy val taskTracker: TaskTracker = new TaskTrackerDelegate(Some(metrics), config, taskTrackerActorRef)

  def taskUpdater: TaskUpdater = taskStateOpProcessor
  def taskCreationHandler: TaskCreationHandler = taskStateOpProcessor
  def stateOpProcessor: TaskStateOpProcessor = taskStateOpProcessor

  private[this] def stateOpResolver(taskTrackerRef: ActorRef): TaskOpProcessorImpl.TaskStateOpResolver =
    new TaskOpProcessorImpl.TaskStateOpResolver(new TaskTrackerDelegate(None, config, taskTrackerRef))
  private[this] def taskOpProcessor(taskTrackerRef: ActorRef): TaskOpProcessor =
    new TaskOpProcessorImpl(taskTrackerRef, taskRepository, stateOpResolver(taskTrackerRef), updateSteps, metrics)
  private[this] lazy val taskUpdaterActorMetrics = new TaskUpdateActor.ActorMetrics(metrics)
  private[this] def taskUpdaterActorProps(taskTrackerRef: ActorRef) =
    TaskUpdateActor.props(clock, taskUpdaterActorMetrics, taskOpProcessor(taskTrackerRef))
  private[this] lazy val taskLoader = new TaskLoaderImpl(taskRepository)
  private[this] lazy val taskTrackerMetrics = new TaskTrackerActor.ActorMetrics(metrics)
  private[this] lazy val taskTrackerActorProps =
    TaskTrackerActor.props(taskTrackerMetrics, taskLoader, taskUpdaterActorProps)
  protected lazy val taskTrackerActorName = "taskTracker"
  private[this] lazy val taskTrackerActorRef = leadershipModule.startWhenLeader(
    taskTrackerActorProps, taskTrackerActorName
  )
  private[this] lazy val taskStateOpProcessor =
    new TaskCreationHandlerAndUpdaterDelegate(clock, config, taskTrackerActorRef)
}
