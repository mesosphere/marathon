package mesosphere.marathon.core.launchqueue

import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ RunSpec, PathId, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.Future

object LaunchQueue {

  /**
    * @param runSpec the currently used run specification.
    * @param tasksLeftToLaunch the tasks that still have to be launched
    * @param taskLaunchesInFlight the number of tasks which have been requested to be launched
    *                        but are unconfirmed yet
    * @param tasksLaunched the number of tasks which are running or at least have been confirmed to be launched
    */
  protected[marathon] case class QueuedTaskInfo(
      runSpec: RunSpec,
      tasksLeftToLaunch: Int,
      taskLaunchesInFlight: Int, // FIXME (217): rename to taskOpsInFlight
      tasksLaunched: Int,
      backOffUntil: Timestamp) {
    /**
      * Indicates if the launch queue tries to launch tasks for this spec.
      */
    def inProgress: Boolean = tasksLeftToLaunch != 0 || taskLaunchesInFlight != 0

    /**
      * This is the final number of tasks, the launch queue tries to reach for this spec.
      */
    def finalTaskCount: Int = tasksLaunched + taskLaunchesInFlight + tasksLeftToLaunch
  }
}

/**
  * The LaunchQueue contains requests to launch new tasks for an run spec.
  */
trait LaunchQueue {

  /** Returns all entries of the queue. */
  def list: Seq[QueuedTaskInfo]
  /** Returns all run specs for which queue entries exist. */
  def listRunSpecs: Seq[RunSpec]

  /** Request to launch `count` additional tasks conforming to the given run spec. */
  def add(runSpec: RunSpec, count: Int = 1): Unit

  /** Get information for the given run spec id. */
  def get(runSpecId: PathId): Option[QueuedTaskInfo]

  /** Return how many tasks are still to be launched for this PathId. */
  def count(runSpecId: PathId): Int

  /** Remove all task launch requests for the given PathId from this queue. */
  def purge(runSpecId: PathId): Unit

  /** Add delay to the given RunSpec because of a failed task */
  def addDelay(runSpec: RunSpec): Unit

  /** Reset the backoff delay for the given RunSpec. */
  def resetDelay(runSpec: RunSpec): Unit

  /** Notify queue about TaskUpdate */
  def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedTaskInfo]]
}
