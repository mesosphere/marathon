package mesosphere.marathon.core.launchqueue

import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.Future

object LaunchQueue {

  /**
    * @param app the currently used app definition
    */
  protected[marathon] case class QueuedTaskInfo(
    app: AppDefinition,
    inProgress: Boolean,
    tasksLeftToLaunch: Int,
    finalTaskCount: Int,
    backOffUntil: Timestamp)
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
