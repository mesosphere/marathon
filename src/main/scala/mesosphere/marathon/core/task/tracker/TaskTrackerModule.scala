package mesosphere.marathon.core.task.tracker

import akka.actor.ActorRef
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.tracker.impl._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.TaskRepository

/**
  * Provides the interfaces to query the current task state ([[TaskTracker]]) and to
  * update the task state ([[TaskUpdater]], [[TaskCreator]]).
  */
class TaskTrackerModule(
    metrics: Metrics,
    config: TaskTrackerConfig,
    leadershipModule: LeadershipModule,
    taskRepository: TaskRepository) {
  lazy val taskTracker: TaskTracker = new TaskTrackerDelegate(config, taskTrackerActorRef)

  def taskUpdater: TaskUpdater = taskTrackerCreatorAndUpdater
  def taskCreator: TaskCreator = taskTrackerCreatorAndUpdater

  private[this] def statusUpdateResolver(taskTrackerRef: ActorRef): TaskOpProcessorImpl.StatusUpdateActionResolver =
    new TaskOpProcessorImpl.StatusUpdateActionResolver(new TaskTrackerDelegate(config, taskTrackerRef))
  private[this] def taskOpProcessor(taskTrackerRef: ActorRef): TaskOpProcessor =
    new TaskOpProcessorImpl(taskTrackerRef, taskRepository, statusUpdateResolver(taskTrackerRef))
  private[this] lazy val taskUpdaterActorMetrics = new TaskUpdateActor.ActorMetrics(metrics)
  private[this] def taskUpdaterActorProps(taskTrackerRef: ActorRef) =
    TaskUpdateActor.props(taskUpdaterActorMetrics, taskOpProcessor(taskTrackerRef))
  private[this] lazy val taskLoader = new TaskLoaderImpl(taskRepository)
  private[this] lazy val taskTrackerActorProps = TaskTrackerActor.props(taskLoader, taskUpdaterActorProps)
  protected lazy val taskTrackerActorName = "taskTracker"
  private[this] lazy val taskTrackerActorRef = leadershipModule.startWhenLeader(
    taskTrackerActorProps, taskTrackerActorName
  )
  private[this] lazy val taskTrackerCreatorAndUpdater = new TaskCreatorAndUpdaterDelegate(config, taskTrackerActorRef)
}
