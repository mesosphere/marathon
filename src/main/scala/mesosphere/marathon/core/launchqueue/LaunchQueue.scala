package mesosphere.marathon.core.launchqueue

import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ PathId, RunnableSpec, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.Future

object LaunchQueue {

  /**
    * @param runSpec the currently used runSpec
    */
  case class QueuedTaskInfo(
    runSpec: RunnableSpec,
    inProgress: Boolean,
    tasksLeftToLaunch: Int,
    finalTaskCount: Int,
    tasksLost: Int,
    backOffUntil: Timestamp)
}

/**
  * The LaunchQueue contains requests to launch new tasks for an run spec.
  */
trait LaunchQueue {

  /** Returns all entries of the queue. */
  def list: Seq[QueuedTaskInfo]
  /** Returns all runnable specs for which queue entries exist. */
  def listRunSpecs: Seq[RunnableSpec]

  /** Request to launch `count` additional tasks conforming to the given run spec. */
  def add(spec: RunnableSpec, count: Int = 1): Unit

  /** Get information for the given run spec id. */
  def get(specId: PathId): Option[QueuedTaskInfo]

  /** Return how many tasks are still to be launched for this PathId. */
  def count(specId: PathId): Int

  /** Remove all task launch requests for the given PathId from this queue. */
  def purge(specId: PathId): Unit

  /** Add delay to the given RunnableSpec because of a failed task */
  def addDelay(spec: RunnableSpec): Unit

  /** Reset the backoff delay for the given RunnableSpec. */
  def resetDelay(spec: RunnableSpec): Unit

  /** Notify queue about TaskUpdate */
  def notifyOfTaskUpdate(taskChanged: TaskChanged): Future[Option[QueuedTaskInfo]]
}
