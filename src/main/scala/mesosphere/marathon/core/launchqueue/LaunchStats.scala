package mesosphere.marathon
package core.launchqueue

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import java.time.Clock
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstancesSnapshot, InstanceChange, InstanceUpdated }
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics.RunSpecOfferStatistics
import mesosphere.marathon.core.launchqueue.impl.{OfferMatchStatistics, RateLimiter}
import mesosphere.marathon.state.{ PathId, RunSpec, RunSpecConfigRef, Timestamp }
import mesosphere.marathon.stream.{EnrichedSink, LiveFold}
import mesosphere.mesos.{NoOfferMatchReason}
import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

/**
  * See LaunchStats$.apply
  */
class LaunchStats private [launchqueue] (
    getRunSpec: PathId => Option[RunSpec],
    delays: LiveFold.Folder[Map[RunSpecConfigRef, Timestamp]],
    launchingInstances: LiveFold.Folder[Map[Instance.Id, LaunchStats.LaunchingInstance]],
    runSpecStatistics: LiveFold.Folder[Map[PathId, RunSpecOfferStatistics]],
    noMatchStatistics: LiveFold.Folder[Map[PathId, Map[String, OfferMatchResult.NoMatch]]])(implicit ec: ExecutionContext) {

  def getStatistics(): Future[Seq[LaunchStats.QueuedInstanceInfoWithStatistics]] = async {
    /**
      * Quick sanity check. These streams should run for the duration of Marathon. In the off chance they aren't, make
      * it obvious.
      */
    require(!launchingInstances.finalResult.isCompleted, s"Launching Instances should not be completed; ${launchingInstances.finalResult}")
    require(!runSpecStatistics.finalResult.isCompleted, s"RunSpecStatistics should not be completed; ${runSpecStatistics.finalResult}")
    require(!noMatchStatistics.finalResult.isCompleted, s"NoMatchStatistics should not be completed, ${noMatchStatistics.finalResult}")
    require(!delays.finalResult.isCompleted, s"Delays should not be completed; ${delays.finalResult}")

    val launchingInstancesByRunSpec = await(launchingInstances.readCurrentResult()).values.groupBy { _.instance.runSpecId }
    val currentDelays = await(delays.readCurrentResult())
    val lastNoMatches = await(noMatchStatistics.readCurrentResult())
    val currentRunSpecStatistics = await(runSpecStatistics.readCurrentResult())

    val results = for {
      (path, instances) <- launchingInstancesByRunSpec
      runSpec <- getRunSpec(path)
    } yield {
      val lastOffers: Seq[OfferMatchResult.NoMatch] = lastNoMatches.get(path).map(_.values.toVector).getOrElse(Nil)
      val statistics = currentRunSpecStatistics.getOrElse(path, RunSpecOfferStatistics.empty)
      val startedAt = if (instances.nonEmpty) instances.iterator.map(_.since).min else Timestamp.now()

      LaunchStats.QueuedInstanceInfoWithStatistics(
        runSpec = runSpec,
        inProgress = true,
        finalInstanceCount = runSpec.instances,
        instancesLeftToLaunch = instances.size,
        backOffUntil = currentDelays.get(runSpec.configRef),
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

  private [launchqueue] case class LaunchingInstance(since: Timestamp, instance: Instance)

  /**
    * Current known list of active instances
    */
  private [launchqueue] val launchingInstancesFold:
      Sink[(Timestamp, InstanceChange), LiveFold.Folder[Map[Instance.Id, LaunchStats.LaunchingInstance]]] =
    EnrichedSink.liveFold(Map.empty[Instance.Id, LaunchingInstance])({ case (instances, (timestamp, change)) =>
      change match {
        case InstanceUpdated(newInstance, _, _) if newInstance.isScheduled || newInstance.isProvisioned =>
          val newRecord = instances.get(change.id)
            .map { launchingInstance => launchingInstance.copy(instance = newInstance) }
            .getOrElse { LaunchingInstance(timestamp, newInstance) }
          instances + (change.id -> newRecord)
        case _ =>
          instances - (change.id)
      }
    })

  /**
    * Given a source of instance updates, delay updates, and offer match statistics updates, materialize the streams and
    * aggregate the resulting data to produce the data returned by /v2/queue
    *
    * @param groupManager
    * @param clock
    * @param instanceUpdates InstanceTracker state subscription stream.
    * @param delayUpdates RateLimiter state subscription stream.
    * @param offerMatchUpdates Series of OfferMatchStatistic updates, as emitted by TaskLauncherActor
    *
    * @return LaunchStats instance used to query the current aggregate match state
    */
  def apply(
    groupManager: GroupManager,
    clock: Clock,
    instanceUpdates: Source[(InstancesSnapshot, Source[InstanceChange, NotUsed]), NotUsed],
    delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    offerMatchUpdates: Source[OfferMatchStatistics.OfferMatchUpdate, NotUsed],
  )(implicit mat: Materializer, ec: ExecutionContext): LaunchStats = {

    val delays = delayUpdates.runWith(delayFold)

    val launchingInstances = instanceUpdates.
      flatMapConcat { case (snapshot, updates) =>
        Source(snapshot.instances.map { i => InstanceUpdated(i, None, Nil) }).concat(updates)
      }.
      map { i => (clock.now(), i) }.runWith(launchingInstancesFold)

    val (runSpecStatistics, noMatchStatistics) =
      offerMatchUpdates
        .alsoToMat(OfferMatchStatistics.runSpecStatisticsSink)(Keep.right)
        .toMat(OfferMatchStatistics.noMatchStatisticsSink)(Keep.both)
        .run
    new LaunchStats(groupManager.runSpec(_), delays, launchingInstances, runSpecStatistics, noMatchStatistics)
  }

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
    backOffUntil: Option[Timestamp],
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
