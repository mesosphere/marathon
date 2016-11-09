package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{ Actor, ActorRef, Props }
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.mesos.NoOfferMatchReason

import scala.collection.mutable
import OfferMatchResult._
import mesosphere.marathon.core.launchqueue.LaunchQueue.{ QueuedInstanceInfoWithStatistics, QueuedInstanceInfo }
import mesosphere.marathon.state.PathId

/**
  * The OfferMatchStatisticsActor is responsible for creating statistics for matched/unmatched offers based on RunSpecId.
  * For every RunSpec this information is maintained:
  * - offer matching statistics
  * - the last offers that are unmatched per agent
  */
class OfferMatchStatisticsActor extends Actor {
  import OfferMatchStatisticsActor._

  private[impl] val runSpecStatistics = mutable.HashMap.empty[PathId, RunSpecOfferStatistics].withDefaultValue(emptyStatistics)
  private[impl] val lastNoMatches = mutable.HashMap.empty[PathId, mutable.HashMap[String, NoMatch]]

  override def receive: Receive = {
    // send whenever an offer has been matched
    case withMatch: Match => updateMatch(withMatch)
    // send whenever an offer has been rejected with a reason
    case noMatch: NoMatch => updateNoMatch(noMatch)
    // send whenever the launch attempt finishes, so the statistics can be reset
    case LaunchFinished(runSpecId) => launchFinished(runSpecId)
    // send when the statistics are requested
    case SendStatistics(to, queueInfo) => sendStatistics(to, queueInfo)
  }

  /**
    * Enhance the given QueuedInstanceInfo objects with available statistics.
    * Create QueuedInstanceInfoWithStatistics objects and send to given actor.
    * @param to the actor to send the result to.
    * @param queueInfos all queueInfo objects to enrich.
    */
  def sendStatistics(to: ActorRef, queueInfos: Seq[QueuedInstanceInfo]): Unit = {
    def withStatistics(queueInfo: QueuedInstanceInfo) = {
      val runSpecId = queueInfo.runSpec.id
      val statistics = runSpecStatistics(runSpecId)
      val lastOffers = lastNoMatches.get(runSpecId).fold(emptyNoMatches)(_.values.toVector)
      QueuedInstanceInfoWithStatistics(
        queueInfo.runSpec,
        queueInfo.inProgress,
        queueInfo.instancesLeftToLaunch,
        queueInfo.finalInstanceCount,
        queueInfo.unreachableInstances,
        queueInfo.backOffUntil,
        queueInfo.startedAt,
        statistics.rejectSummary,
        statistics.processedOfferCount,
        statistics.unusedOfferCount,
        statistics.lastMatch,
        statistics.lastNoMatch,
        lastOffers
      )
    }
    to ! queueInfos.map(withStatistics)
  }

  /**
    * Update internal statistics by incorporating this Match.
    */
  def updateMatch(withMatch: Match): Unit = {
    val current = runSpecStatistics(withMatch.runSpec.id)
    runSpecStatistics.update(withMatch.runSpec.id, current.incrementMatched(withMatch))
  }

  /**
    * Update internal statistics by incorporating this NoMatch.
    */
  def updateNoMatch(noMatch: NoMatch): Unit = {
    val current = runSpecStatistics(noMatch.runSpec.id)
    runSpecStatistics.update(noMatch.runSpec.id, current.incrementUnmatched(noMatch))
    val lastNoMatch = lastNoMatches.getOrElseUpdate(noMatch.runSpec.id, mutable.HashMap.empty)
    lastNoMatch.update(noMatch.offer.getSlaveId.getValue, noMatch)
  }

  /**
    * Update internal statistics by resetting statistics for given runSpecId
    */
  def launchFinished(runSpecId: PathId): Unit = {
    runSpecStatistics -= runSpecId
    lastNoMatches -= runSpecId
  }
}

object OfferMatchStatisticsActor {

  /**
    * This class represents the statistics maintained for one run specification.
    */
  case class RunSpecOfferStatistics(
      rejectSummary: Map[NoOfferMatchReason, Int],
      processedOfferCount: Int,
      unusedOfferCount: Int,
      lastMatch: Option[Match],
      lastNoMatch: Option[NoMatch]
  ) {
    def incrementMatched(withMatched: Match): RunSpecOfferStatistics = copy(
      processedOfferCount = processedOfferCount + 1,
      lastMatch = Some(withMatched)
    )

    def incrementUnmatched(noMatch: NoMatch): RunSpecOfferStatistics = {
      import NoOfferMatchReason._
      def updateSummary: Map[NoOfferMatchReason, Int] = {
        // stage 1: if unmatched role, ignore everything else
        val reasons = noMatch.reasons.find(_ == UnmatchedRole)
          // stage 2: if unmatched constraint, ignore everything else
          .orElse(noMatch.reasons.find(_ == UnmatchedConstraint))
          // stage 3: if both are not defined, use all noMatch reasons
          .fold(noMatch.reasons)(Seq(_))
        reasons.foldLeft(rejectSummary) { (map, reason) => map.updated(reason, map(reason) + 1) }
      }
      copy(
        processedOfferCount = processedOfferCount + 1,
        unusedOfferCount = unusedOfferCount + 1,
        lastNoMatch = Some(noMatch),
        rejectSummary = updateSummary
      )
    }
  }

  val emptyStatistics = RunSpecOfferStatistics(Map.empty.withDefaultValue(0), 0, 0, None, None)
  val emptyNoMatches = Seq.empty[NoMatch]

  case class LaunchFinished(runSpecId: PathId)
  case class SendStatistics(to: ActorRef, queueInfos: Seq[QueuedInstanceInfo])

  def props(): Props = Props(new OfferMatchStatisticsActor)
}
