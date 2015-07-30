package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.{ OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.MatchedTasks
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Passes processed offers to the offerMatcher and launches the appropriate tasks.
  */
private[launcher] class OfferProcessorImpl(
    conf: OfferProcessorConfig, clock: Clock,
    metrics: Metrics,
    offerMatcher: OfferMatcher, taskLauncher: TaskLauncher) extends OfferProcessor {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val offerMatchingTimeout = conf.offerMatchingTimeout().millis

  private[this] val incomingOffersMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "incomingOffers"))
  private[this] val matchTimeMeter = metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "matchTime"))

  override def processOffer(offer: Offer): Future[Unit] = {
    incomingOffersMeter.mark()

    val deadline = clock.now() + offerMatchingTimeout

    val matchFuture: Future[MatchedTasks] = matchTimeMeter.timeFuture {
      offerMatcher.matchOffer(deadline, offer)
    }

    matchFuture
      .recover {
        case NonFatal(e) =>
          log.error(s"error while matching '${offer.getId.getValue}'", e)
          MatchedTasks(offer.getId, Seq.empty)
      }.map {
        case MatchedTasks(offerId, tasks) =>
          if (tasks.nonEmpty) {
            if (taskLauncher.launchTasks(offerId, tasks.map(_.taskInfo))) {
              log.debug("task launch successful for {}", offerId.getValue)
              tasks.foreach(_.accept())
            }
            else {
              log.warn("task launch rejected for {}", offerId.getValue)
              tasks.foreach(_.reject("driver unavailable"))
            }
          }
          else {
            taskLauncher.declineOffer(offerId)
          }
      }
  }
}
