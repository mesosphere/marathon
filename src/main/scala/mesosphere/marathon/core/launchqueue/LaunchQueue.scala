package mesosphere.marathon.core.launchqueue

import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.Future

object LaunchQueue {

  /**
    * @param app the currently used app definition
    * @param tasksLeftToLaunch the tasks that still have to be launched
    * @param taskLaunchesInFlight the number of tasks which have been requested to be launched
    *                        but are unconfirmed yet
    * @param tasksLaunched the number of tasks which are running or at least have been confirmed to be launched
    */
  protected[marathon] case class QueuedTaskInfo(
      app: AppDefinition,
      tasksLeftToLaunch: Int,
      taskLaunchesInFlight: Int, // FIXME (217): rename to taskOpsInFlight
      tasksLaunched: Int,
      backOffUntil: Timestamp) {
    /**
      * Indicates if the launch queue tries to launch tasks for this app.
      */
    def inProgress: Boolean = tasksLeftToLaunch != 0 || taskLaunchesInFlight != 0

    /**
      * This is the final number of tasks, the launch queue tries to reach for this app.
      */
    def finalTaskCount: Int = tasksLaunched + taskLaunchesInFlight + tasksLeftToLaunch
  }
}

/**
  * The LaunchQueue contains requests to launch new tasks for an application.
  */
trait LaunchQueue {

  /** Returns all entries of the queue. */
  def list: Seq[QueuedTaskInfo]
  /** Returns all apps for which queue entries exist. */
  def listApps: Seq[AppDefinition]

  /** Request to launch `count` additional tasks conforming to the given app definition. */
  def add(app: AppDefinition, count: Int = 1): Unit

  /** Get information for the given appId. */
  def get(appId: PathId): Option[QueuedTaskInfo]

  /** Return how many tasks are still to be launched for this PathId. */
  def count(appId: PathId): Int

  /** Remove all task launch requests for the given PathId from this queue. */
  def purge(appId: PathId): Unit

  /** Add delay to the given AppDefinition because of a failed task */
  def addDelay(app: AppDefinition): Unit

  /** Reset the backoff delay for the given AppDefinition. */
  def resetDelay(app: AppDefinition): Unit

  /** Notify queue about TaskUpdate */
  def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedTaskInfo]]
}
