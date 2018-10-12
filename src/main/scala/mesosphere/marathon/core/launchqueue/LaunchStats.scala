package mesosphere.marathon
package core.launchqueue

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source }
import com.typesafe.scalalogging.StrictLogging
import java.time.Clock
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics.RunSpecOfferStatistics
import mesosphere.marathon.core.launchqueue.impl.{OfferMatchStatistics, RateLimiter}
import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.state.{PathId, RunSpecConfigRef, Timestamp}
import mesosphere.marathon.stream.{EnrichedSink, LiveFold}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.mesos.{NoOfferMatchReason}
import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

class LaunchStats private (
    groupManager: GroupManager,
    clock: Clock,
    delays: LiveFold.Folder[Map[RunSpecConfigRef, Timestamp]],
    launchingInstances: LiveFold.Folder[Map[Instance.Id, LaunchStats.LaunchingInstance]],
    runSpecStatistics: LiveFold.Folder[Map[PathId, RunSpecOfferStatistics]],
    noMatchStatistics: LiveFold.Folder[Map[PathId, Map[String, OfferMatchResult.NoMatch]]])(implicit ec: ExecutionContext) {

  def getStatistics(): Future[Seq[LaunchStats.QueuedInstanceInfoWithStatistics]] = async {
    /**
      * Quick sanity check. These streams should run for the duration of Marathon. In the off chance they aren't, make
      * it obvious.
      */
    require(!launchingInstances.finalResult.isCompleted, "Launching Instances should not be completed")
    require(!runSpecStatistics.finalResult.isCompleted, "RunSpecStatistics should not be completed")
    require(!noMatchStatistics.finalResult.isCompleted, "NoMatchStatistics should not be completed")
    require(!delays.finalResult.isCompleted, "Delays should not be completed")

    val launchingInstancesByRunSpec = await(launchingInstances.readCurrentResult()).values.groupBy { _.instance.runSpecId }
    val currentDelays = await(delays.readCurrentResult())
    val lastNoMatches = await(noMatchStatistics.readCurrentResult())
    val currentRunSpecStatistics = await(runSpecStatistics.readCurrentResult())

    val results = for {
      (path, instances) <- launchingInstancesByRunSpec
      runSpec <- groupManager.runSpec(path)
    } yield {
      val lastOffers = lastNoMatches.get(path).fold(Seq.empty[OfferMatchResult.NoMatch])(_.values.toVector)
      val statistics = currentRunSpecStatistics(path)
      val startedAt = if (instances.nonEmpty) instances.map(_.since).min else Timestamp.now()

      LaunchStats.QueuedInstanceInfoWithStatistics(
        runSpec = runSpec,
        inProgress = true,
        finalInstanceCount = runSpec.instances,
        instancesLeftToLaunch = instances.size,
        backOffUntil = currentDelays.getOrElse(runSpec.configRef, Timestamp.now(clock)),
        startedAt = startedAt,
        rejectSummaryLastOffers = lastOfferSummary(lastOffers),
        rejectSummaryLaunchAttempt = statistics.rejectSummary,
        processedOffersCount = statistics.processedOfferCount,
        unusedOffersCount = statistics.unusedOfferCount,
        lastMatch = statistics.lastMatch,
        lastNoMatch = statistics.lastNoMatch,
        lastNoMatches = lastOffers)
    }
    results.toList
  }

  private def lastOfferSummary(lastOffers: Seq[OfferMatchResult.NoMatch]): Map[NoOfferMatchReason, Int] = {
    lastOffers.withFilter(_.reasons.nonEmpty)
      .map(_.reasons.minBy(OfferMatchStatistics.reasonFunnelPriority))
      .groupBy(identity).map { case (id, reasons) => id -> reasons.size }
  }
}

object LaunchStats extends StrictLogging {
  // Current known list of delays
  private val delayFold = EnrichedSink.liveFold(Map.empty[RunSpecConfigRef, Timestamp])({ (delays, delayUpdate: RateLimiter.DelayUpdate) =>
    delayUpdate.delayUntil match {
      case Some(instant) =>
        delays + (delayUpdate.ref -> instant)
      case None =>
        delays - delayUpdate.ref
    }
  })

  /**
    * Current known list of active instances
    */
  private def launchingInstancesFold(clock: Clock) =
    EnrichedSink.liveFold(Map.empty[Instance.Id, LaunchingInstance])({ (instances, update: InstanceTracker.InstanceUpdate) =>
      update.value match {
        case Some(instance) if instance.isScheduled || instance.isProvisioned =>
          val newRecord = instances.get(update.instanceId) match {
            case Some(launchingInstance) =>
              launchingInstance.copy(instance = instance)
            case None =>
              LaunchingInstance(Timestamp.now(clock), instance)
          }
          instances.updated(update.instanceId, newRecord)
        case _ =>
          instances - (update.instanceId)
      }
    })

  def apply(
    groupManager: GroupManager,
    clock: Clock,
    instanceUpdates: Source[InstanceTracker.InstanceUpdate, NotUsed],
    delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    offerMatchUpdates: Source[OfferMatchStatistics.OfferMatchUpdate, NotUsed],
  )(implicit mat: Materializer, ec: ExecutionContext): LaunchStats = {

    val delays = delayUpdates.runWith(delayFold)

    val launchingInstances = instanceUpdates.runWith(launchingInstancesFold(clock))

    val (runSpecStatistics, noMatchStatistics) =
      offerMatchUpdates
        .alsoToMat(OfferMatchStatistics.runSpecStatisticsSink)(Keep.right)
        .toMat(OfferMatchStatistics.noMatchStatisticsSink)(Keep.both)
        .run
    new LaunchStats(groupManager, clock, delays, launchingInstances, runSpecStatistics, noMatchStatistics)
  }

  private case class LaunchingInstance(since: Timestamp, instance: Instance)

  /**
    * @param runSpec the associated runSpec
    * @param inProgress true if the launch queue currently tries to launch more instances
    * @param instancesLeftToLaunch number of instances to launch
    * @param finalInstanceCount the final number of instances currently targeted
    * @param backOffUntil timestamp until which no further launch attempts will be made
    */
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
