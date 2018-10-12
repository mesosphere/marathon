package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.scaladsl.Sink
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.stream.{EnrichedSink, LiveFold}
import mesosphere.mesos.NoOfferMatchReason

import OfferMatchResult._
import mesosphere.marathon.state.PathId

/**
  * The OfferMatchStatistics is responsible for creating statistics for matched/unmatched offers based on RunSpecId.
  * For every RunSpec this information is maintained:
  * - offer matching statistics
  * - the last offers that are unmatched per agent
  */
object OfferMatchStatistics {

  def runSpecStatisticsSink: Sink[OfferMatchUpdate, LiveFold.Folder[Map[PathId, RunSpecOfferStatistics]]] = {
    val zero = Map.empty[PathId, RunSpecOfferStatistics].withDefaultValue(emptyStatistics)
    EnrichedSink.liveFold(zero) { (runSpecStatistics, offerMatchUpdate: OfferMatchUpdate) =>
      offerMatchUpdate match {
        // send whenever an offer has been matched
        case MatchResult(withMatch: Match) =>
          val current = runSpecStatistics(withMatch.runSpec.id)
          runSpecStatistics + (withMatch.runSpec.id -> current.incrementMatched(withMatch))

        case MatchResult(noMatch: NoMatch) =>
          val current = runSpecStatistics(noMatch.runSpec.id)
          runSpecStatistics + (noMatch.runSpec.id -> current.incrementUnmatched(noMatch))

        case LaunchFinished(runSpecId) =>
          runSpecStatistics - runSpecId
      }
    }
  }

  def noMatchStatisticsSink: Sink[OfferMatchUpdate, LiveFold.Folder[Map[PathId, Map[String, NoMatch]]]] = {
    val zero = Map.empty[PathId, Map[String, NoMatch]].withDefaultValue(Map.empty)
    EnrichedSink.liveFold(zero) { (lastNoMatches, offerMatchUpdate: OfferMatchUpdate) =>
      offerMatchUpdate match {
        // send whenever an offer has been matched
        case MatchResult(withMatch: Match) =>
          // noop
          lastNoMatches

        case MatchResult(noMatch: NoMatch) =>
          val path = noMatch.runSpec.id
          val newNoMatches = lastNoMatches(path) + (noMatch.offer.getSlaveId.getValue -> noMatch)
          lastNoMatches + (path -> newNoMatches)

        case LaunchFinished(runSpecId) =>
          lastNoMatches - runSpecId
      }
    }
  }

  private val emptyStatistics = RunSpecOfferStatistics(Map.empty.withDefaultValue(0), 0, 0, None, None)
  val reasonFunnelPriority: Map[NoOfferMatchReason, Int] = NoOfferMatchReason.reasonFunnel.zipWithIndex.toMap

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
      def updatedSummary: Map[NoOfferMatchReason, Int] = {
        val reason = noMatch.reasons.minBy(reasonFunnelPriority)
        rejectSummary.updated(reason, rejectSummary(reason) + 1)
      }
      copy(
        processedOfferCount = processedOfferCount + 1,
        unusedOfferCount = unusedOfferCount + 1,
        lastNoMatch = Some(noMatch),
        rejectSummary = if (noMatch.reasons.isEmpty) rejectSummary else updatedSummary
      )
    }
  }

  sealed trait OfferMatchUpdate
  case class LaunchFinished(runSpecId: PathId) extends OfferMatchUpdate
  case class MatchResult(matchResult: OfferMatchResult) extends OfferMatchUpdate
}
