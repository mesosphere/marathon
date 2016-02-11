package mesosphere.marathon.core.launcher.impl

import java.util.Collections

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.TaskLauncher
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.TaskOp
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import org.apache.mesos.Protos.{ Offer, OfferID, Status }
import org.apache.mesos.{ Protos, SchedulerDriver }
import org.slf4j.LoggerFactory

private[launcher] class TaskLauncherImpl(
    metrics: Metrics,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    clock: Clock) extends TaskLauncher {
  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val usedOffersMeter = metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "usedOffers"))
  private[this] val launchedTasksMeter = metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "launchedTasks"))
  private[this] val declinedOffersMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "declinedOffers"))

  override def acceptOffer(offerID: OfferID, taskOps: Seq[TaskOp]): Boolean = {
    val launched = withDriver(s"launchTasks($offerID)") { driver =>
      import scala.collection.JavaConverters._
      if (log.isDebugEnabled) {
        log.debug(s"Operations on $offerID:\n${taskOps.mkString("\n")}")
      }

      //We accept the offer, the rest of the offer is declined automatically with the given filter.
      //The filter duration is set to 0, so we get the same offer in the next allocator cycle.
      val noFilter = Protos.Filters.newBuilder().setRefuseSeconds(0).build()
      val operations = taskOps.flatMap(_.offerOperations)
      driver.acceptOffers(Collections.singleton(offerID), operations.asJava, noFilter)
    }
    if (launched) {
      usedOffersMeter.mark()
      val launchCount = taskOps.count {
        case OfferMatcher.Launch(_, _, _) => true
        case _                            => false
      }
      launchedTasksMeter.mark(launchCount)
    }
    launched
  }

  override def declineOffer(offerID: OfferID, refuseMilliseconds: Option[Long]): Unit = {
    val declined = withDriver(s"declineOffer(${offerID.getValue})") {
      val filters = refuseMilliseconds
        .map(seconds => Protos.Filters.newBuilder().setRefuseSeconds(seconds / 1000.0).build())
        .getOrElse(Protos.Filters.getDefaultInstance)
      _.declineOffer(offerID, filters)
    }
    if (declined) {
      declinedOffersMeter.mark()
    }
  }

  private[this] def withDriver(description: => String)(block: SchedulerDriver => Status): Boolean = {
    marathonSchedulerDriverHolder.driver match {
      case Some(driver) =>
        val status = block(driver)
        if (log.isDebugEnabled) {
          log.debug(s"$description returned status = $status")
        }
        status == Status.DRIVER_RUNNING

      case None =>
        log.warn(s"Cannot execute '$description', no driver available")
        false
    }
  }
}
