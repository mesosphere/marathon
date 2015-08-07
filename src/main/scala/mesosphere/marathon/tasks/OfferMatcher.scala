package mesosphere.marathon.tasks

import java.util

import com.google.inject.{ Inject }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.metrics.Metrics.{ Timer, Histogram, Meter }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

//scalastyle:off magic.number

trait OfferMatcher {
  /**
    * Process the given offers. All offers should either be used for launching tasks or declined
    * via the given driver.
    */
  def processResourceOffers(driver: SchedulerDriver, offersList: Iterable[Offer]): Unit
}

trait IterativeOfferMatcherConfig extends ScallopConf {
  lazy val declineOfferDuration = opt[Long]("decline_offer_duration",
    descr = "(Default: Use mesos default of 5 seconds) " +
      "The duration (milliseconds) for which to decline offers by default",
    default = None)

  lazy val maxTasksPerOffer = opt[Int]("max_tasks_per_offer",
    descr = "Maximally launch this number of tasks per offer.",
    default = Some(1),
    noshort = true)

  lazy val maxTasksPerOfferCycle = opt[Int]("max_tasks_per_offer_cycle",
    descr = "Maximally launch this number of tasks per offer cycle.",
    default = Some(1000),
    noshort = true)
}

class IterativeOfferMatcherMetrics @Inject() (metrics: Metrics) {
  def prefix: String = MetricPrefixes.SERVICE

  val tasksLaunched: Meter = metrics.meter(metrics.name(prefix, getClass, "tasks-launched"))
  val tasksLaunchedPerOffer: Histogram = metrics.histogram(metrics.name(prefix, getClass, "tasks-launched-per-offer"))
  val offersDeclined: Meter = metrics.meter(metrics.name(prefix, getClass, "offers-declined"))

  val calculateOfferUsageTimer: Timer = metrics.timer(metrics.name(prefix, getClass, "calculate-offer-usages"))
  val commitOfferUsagesToDriverTimer: Timer =
    metrics.timer(metrics.name(prefix, getClass, "commit-offer-usages-to-driver"))
  val matchingOfferTimer: Timer = metrics.timer(metrics.name(prefix, getClass, "matching-offer"))
}

object IterativeOfferMatcher {
  private val log = LoggerFactory.getLogger(getClass)

  /**
    * @param hot [[OfferUsage]]s which can still potentially satisfy scheduled task resources
    * @param depleted [[OfferUsages]]s which we already failed to match in the last cycle
    */
  case class OfferUsages(hot: Vector[OfferUsage] = Vector.empty, depleted: Vector[OfferUsage] = Vector.empty) {
    def addDepleted(offer: OfferUsage): OfferUsages = copy(depleted = depleted :+ offer)
    def addHot(offer: OfferUsage): OfferUsages = copy(hot = hot :+ offer)

    def usages: Vector[OfferUsage] = hot ++ depleted
  }

  /**
    * An offer with the tasks to be launched on it.
    */
  case class OfferUsage(remainingOffer: Offer, scheduledTasks: Vector[TaskInfo] = Vector.empty) {
    def addTask(taskInfo: TaskInfo): OfferUsage = {
      copy(
        scheduledTasks = scheduledTasks :+ taskInfo,
        remainingOffer = deductTask(remainingOffer, taskInfo)
      )
    }

    /** Deducts the resources used by the task from the given offer. */
    private[this] def deductTask(offer: Offer, taskInfo: TaskInfo): Offer = {
      val newResources = ResourceUtil.consumeResources(
        offer.getResourcesList.asScala, taskInfo.getResourcesList.asScala)

      if (log.isDebugEnabled) {
        log.debug("used resources {}", ResourceUtil.displayResources(taskInfo.getResourcesList.asScala))
      }

      offer.toBuilder.clearResources().addAllResources(newResources.asJava).build()
    }
  }
}

class IterativeOfferMatcher @Inject() (
  iterativeOfferMatcherConfig: IterativeOfferMatcherConfig,
  taskQueue: TaskQueue,
  taskTracker: TaskTracker,
  taskFactory: TaskFactory,
  metrics: IterativeOfferMatcherMetrics)
    extends OfferMatcher {

  import IterativeOfferMatcher._

  private[this] val maxTasksPerOffer: Int = iterativeOfferMatcherConfig.maxTasksPerOffer.get.getOrElse(1)
  require(maxTasksPerOffer > 0, "We need to be allowed to launch at least one task per offer")
  private[this] val maxTasksPerCycle: Int = iterativeOfferMatcherConfig.maxTasksPerOfferCycle.get.getOrElse(1000)
  require(maxTasksPerCycle > 0, "We need to be allowed to launch at least one task per offer cycle")

  def processResourceOffers(driver: SchedulerDriver, offersList: Iterable[Offer]): Unit = {
    val finalOfferUsages: OfferUsages = metrics.calculateOfferUsageTimer {
      calculateOfferUsage(offersList)
    }

    metrics.commitOfferUsagesToDriverTimer {
      commitOfferUsagesToDriver(driver, finalOfferUsages)
    }
  }

  /**
    * Calculates the [[OfferUsages]] for the given offers.
    *
    * This method is not side-effect free:
    * * new tasks are registered in the taskTracker
    * * scheduled tasks are removed from the taskQueue
    */
  //scalastyle:off method.length
  private[tasks] def calculateOfferUsage(offersList: Iterable[Offer]): OfferUsages = {
    log.info("started processing {} offers, launching at most {} tasks per offer and {} tasks in total",
      Seq(offersList.size, maxTasksPerOffer, maxTasksPerCycle).map(_.asInstanceOf[AnyRef]): _*)

    var allowedTaskLaunches = maxTasksPerCycle

    def tryMatchingOffer(processed: OfferUsages, hot: OfferUsage): OfferUsages = {
      val offer = hot.remainingOffer

      if (log.isDebugEnabled) {
        log.debug("try matching offer = {}", ResourceUtil.displayResources(offer.getResourcesList.asScala))
      }

      try {
        val offerMatch = metrics.matchingOfferTimer {
          taskQueue.pollMatching { app =>
            taskFactory.newTask(app, offer).map(app -> _)
          }
        }

        offerMatch match {
          case Some((app: AppDefinition, CreatedTask(taskInfo: TaskInfo, marathonTask: MarathonTask))) =>

            allowedTaskLaunches -= 1
            log.debug("Adding task for launching ({} tasks left in cycle): {}", allowedTaskLaunches, taskInfo)
            taskTracker.created(app.id, marathonTask)
            processed.addHot(hot.addTask(taskInfo))

          // here it is assumed that the health checks for the current
          // version are already running.
          case None =>
            processed.addDepleted(hot)
        }
      }
      catch {
        case t: Throwable =>
          log.error("Caught an exception. Declining offer.", t)
          processed.addDepleted(hot)
      }

    }

    def matchOffers(offers: OfferUsages): OfferUsages = {
      log.debug(s"processing ${offers.hot.size} hot offers")
      offers.hot.foldLeft(OfferUsages(depleted = offers.depleted)) {
        case (processed, hot) =>
          if (allowedTaskLaunches > 0)
            tryMatchingOffer(processed, hot)
          else
            processed.addDepleted(hot)
      }
    }

    val offerIterator = new Iterator[OfferUsages] {
      private[this] var offers = OfferUsages(hot = offersList.map(OfferUsage(_)).toVector)
      override def hasNext: Boolean = offers.hot.nonEmpty
      override def next(): OfferUsages = {
        offers = matchOffers(offers)
        offers
      }
    }

    val processStream = offerIterator.toStream.take(maxTasksPerOffer)
    processStream.last
  }

  private[tasks] def commitOfferUsagesToDriver(driver: SchedulerDriver, finalOfferUsage: OfferUsages): Unit = {
    val numberOfTasksToLaunch: Int = finalOfferUsage.usages.map(_.scheduledTasks.size).sum
    // if the taskLaunchLimit was hit, we might need to get offers back again faster
    val taskLaunchLimitHit: Boolean = numberOfTasksToLaunch >= maxTasksPerCycle

    var usedOffers: Long = 0
    var launchedTasks: Long = 0
    var declinedOffers: Long = 0
    for (offerUsage <- finalOfferUsage.usages) {
      val offer = offerUsage.remainingOffer
      if (offerUsage.scheduledTasks.nonEmpty) {
        val offerIds: util.Collection[OfferID] = Seq(offer.getId).asJavaCollection
        val tasks: util.Collection[TaskInfo] = offerUsage.scheduledTasks.asJavaCollection
        if (log.isDebugEnabled) {
          log.debug("Offer [{}]. Launch tasks {}",
            Seq(offer.getId.getValue, tasks.asScala.map(_.getTaskId.getValue).mkString(", ")): _*)
        }
        driver.launchTasks(offerIds, tasks)
        metrics.tasksLaunchedPerOffer.update(tasks.size())
        usedOffers += 1
        launchedTasks += tasks.size()
      }
      else {
        iterativeOfferMatcherConfig.declineOfferDuration.get match {
          case Some(durationInMs) if !taskLaunchLimitHit =>
            val filter = Filters.newBuilder().setRefuseSeconds(durationInMs / 1000.0).build()
            log.info(s"Offer [${offer.getId.getValue}]. Decline with filter refuseSeconds=${filter.getRefuseSeconds}" +
              s"(use --${iterativeOfferMatcherConfig.declineOfferDuration.name} to reconfigure)")
            driver.declineOffer(offer.getId, filter)
          case Some(durationInMs) if taskLaunchLimitHit =>
            log.info(s"Offer [${offer.getId.getValue}]. " +
              s"Task launch limit reached. Decline with default filter refuseSeconds.")
            driver.declineOffer(offer.getId)
          case None =>
            log.info(s"Offer [${offer.getId.getValue}]. Decline with default filter refuseSeconds " +
              s"(use --${iterativeOfferMatcherConfig.declineOfferDuration.name} to configure)")
            driver.declineOffer(offer.getId)
        }
        declinedOffers += 1
      }
    }

    metrics.tasksLaunched.mark(launchedTasks)
    metrics.offersDeclined.mark(declinedOffers)
    log.info(s"Launched $launchedTasks tasks on $usedOffers offers, declining $declinedOffers")
  }
}
