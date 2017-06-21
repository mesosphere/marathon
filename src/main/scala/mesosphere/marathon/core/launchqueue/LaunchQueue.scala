package mesosphere.marathon
package core.launchqueue

import akka.Done
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue.{ QueuedInstanceInfo, QueuedInstanceInfoWithStatistics }
import mesosphere.marathon.state.{ PathId, RunSpec, Timestamp }
import mesosphere.mesos.NoOfferMatchReason

import scala.collection.immutable.Seq
import scala.concurrent.Future

object LaunchQueue {

  /**
    * @param runSpec the associated runSpec
    * @param inProgress true if the launch queue currently tries to launch more instances
    * @param instancesLeftToLaunch number of instances to launch
    * @param finalInstanceCount the final number of instances currently targeted
    * @param backOffUntil timestamp until which no further launch attempts will be made
    */
  case class QueuedInstanceInfo(
    runSpec: RunSpec,
    inProgress: Boolean,
    instancesLeftToLaunch: Int,
    finalInstanceCount: Int,
    backOffUntil: Timestamp,
    startedAt: Timestamp)

  case class QueuedInstanceInfoWithStatistics(
    runSpec: RunSpec,
    inProgress: Boolean,
    instancesLeftToLaunch: Int,
    finalInstanceCount: Int,
    backOffUntil: Timestamp,
    startedAt: Timestamp,
    rejectSummaryLastOffers: Map[NoOfferMatchReason, Int],
    rejectSummaryLaunchAttempt: Map[NoOfferMatchReason, Int],
    processedOffersCount: Int,
    unusedOffersCount: Int,
    lastMatch: Option[OfferMatchResult.Match],
    lastNoMatch: Option[OfferMatchResult.NoMatch],
    lastNoMatches: Seq[OfferMatchResult.NoMatch]
  )
}

/**
  * The LaunchQueue contains requests to launch new instances for a run spec.
  */
trait LaunchQueue {

  /** Returns all entries of the queue. */
  def list: Seq[QueuedInstanceInfo]

  /** Returns all entries of the queue with embedded statistics */
  def listWithStatistics: Seq[QueuedInstanceInfoWithStatistics]

  /** Returns all runnable specs for which queue entries exist. */
  def listRunSpecs: Seq[RunSpec]

  /** Request to launch `count` additional instances conforming to the given run spec. */
  def add(spec: RunSpec, count: Int = 1): Unit

  /** Get information for the given run spec id. */
  def get(specId: PathId): Option[QueuedInstanceInfo]

  /** Return how many instances are still to be launched for this PathId. */
  def count(specId: PathId): Int

  /** Remove all instance launch requests for the given PathId from this queue. */
  def asyncPurge(specId: PathId): Future[Done]

  /** Add delay to the given RunnableSpec because of a failed instance */
  def addDelay(spec: RunSpec): Unit

  /** Reset the backoff delay for the given RunnableSpec. */
  def resetDelay(spec: RunSpec): Unit

  /** Notify queue about InstanceUpdate */
  def notifyOfInstanceUpdate(update: InstanceChange): Future[Done]
}
