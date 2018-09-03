package mesosphere.marathon
package core.launcher.impl

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.launcher.{InstanceOp, OfferProcessor, OfferProcessorConfig, TaskLauncher}
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.deprecated.ServiceMetric
import org.apache.mesos.Protos.{Offer, OfferID}

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.collection.JavaConverters._

/**
  * Passes processed offers to the offerMatcher and launches the appropriate tasks.
  *
  * {{{
  * @startuml
  * participant MarathonScheduler
  * participant OfferProcessorImpl
  * participant OfferMatcherManagerActor
  * participant TaskLauncherActor
  * participant TaskLauncherImpl
  *
  * rnote over TaskLauncherActor
  * Every RunSpec to launch has its
  * own actor.The OfferMatcherManager
  * will ask every TaskLauncherActor
  * one by one.
  * endrnote
  *
  * MarathonScheduler -> OfferProcessorImpl: processOffer(offer)
  * OfferProcessorImpl -> OfferMatcherManagerActor: matchOffer(offer)
  * OfferProcessorImpl --> MarathonScheduler: Done
  * OfferMatcherManagerActor -> OfferMatcherManagerActor: Queue offer if all matchers are busy
  * loop until there is no matcher left, offer exhausted or timeout reached
  *      OfferMatcherManagerActor -> TaskLauncherActor: matchOffer(offer): MatchedInstanceOp
  *      TaskLauncherActor -> OfferMatcherManagerActor: MatchOffer(promise)
  *      OfferMatcherManagerActor -> OfferMatcherManagerActor: MatchedInstanceOps(offer, addedOps, resendOffer)
  *      OfferMatcherManagerActor -> OfferMatcherManagerActor: scheduleNextMatcherOrFinish(nextData)
  * end
  * OfferMatcherManagerActor --> OfferProcessorImpl : MatchedInstanceOps
  * OfferProcessorImpl -> TaskLauncherImpl: acceptOffer | declineOffer
  * @enduml
  * }}}
  */
private[launcher] class OfferProcessorImpl(
    metrics: Metrics,
    conf: OfferProcessorConfig,
    offerMatcher: OfferMatcher,
    taskLauncher: TaskLauncher,
    instanceTracker: InstanceTracker) extends OfferProcessor with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val oldIncomingOffersMetric =
    metrics.deprecatedMinMaxCounter(ServiceMetric, getClass, "incomingOffers")
  private[this] val newIncomingOffersMetric =
    metrics.counter("mesos.offers.incoming")
  private[this] val oldMatchTimeMetric =
    metrics.deprecatedTimer(ServiceMetric, getClass, "matchTime")
  private[this] val newMatchTimeMetric =
    metrics.timer("debug.mesos.offers.matching-duration")
  private[this] val oldMatchErrorsMetric =
    metrics.deprecatedMinMaxCounter(ServiceMetric, getClass, "matchErrors")
  private[this] val newMatchErrorsMetric =
    metrics.counter("debug.mesos.offers.unprocessable")
  private[this] val oldSavingTasksTimeMetric =
    metrics.deprecatedTimer(ServiceMetric, getClass, "savingTasks")
  private[this] val newSavingTasksTimeMetric =
    metrics.timer("debug.mesos.offers.saving-tasks-duration")
  private[this] val oldSavingTasksErrorMetric =
    metrics.deprecatedMinMaxCounter(ServiceMetric, getClass, "savingTasksErrors")
  private[this] val newSavingTasksErrorMetric =
    metrics.counter("debug.mesos.offers.saving-tasks-errors")

  private def warnOnZeroResource(offer: Offer): Unit = {
    val resourcesWithZeroValues = offer
      .getResourcesList.asScala
      .collect {
        case resource if resource.getScalar.getValue.ceil.toLong == 0 =>
          resource.getName
      }
    if (resourcesWithZeroValues.nonEmpty) {
      logger.warn(s"Offer ${offer.getId.getValue} has zero-valued resources: ${resourcesWithZeroValues.mkString(", ")}")
    }
  }

  private def logOffer(offer: Offer): Unit = {
    val offerId = offer.getId.getValue
    val agentId = offer.getSlaveId.getValue
    logger.info(s"Processing offer: offerId $offerId, agentId $agentId")
  }

  override def processOffer(offer: Offer): Future[Done] = {
    oldIncomingOffersMetric.increment()
    newIncomingOffersMetric.increment()
    logOffer(offer)
    warnOnZeroResource(offer)

    val matchFuture: Future[MatchedInstanceOps] = oldMatchTimeMetric {
      newMatchTimeMetric {
        offerMatcher.matchOffer(offer)
      }
    }

    matchFuture
      .recover {
        case NonFatal(e) =>
          oldMatchErrorsMetric.increment()
          newMatchErrorsMetric.increment()
          logger.error(s"Could not process offer '${offer.getId.getValue}'", e)
          MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = true)
      }.flatMap {
        case MatchedInstanceOps(offerId, opsWithSource, resendThisOffer) =>
          oldSavingTasksTimeMetric {
            newSavingTasksTimeMetric {
              saveTasks(opsWithSource).map { savedTasks =>
                def notAllSaved: Boolean = savedTasks.size != opsWithSource.size
                MatchedInstanceOps(offerId, savedTasks, resendThisOffer || notAllSaved)
              }
            }
          }
      }.flatMap {
        case MatchedInstanceOps(offerId, Nil, resendThisOffer) => declineOffer(offerId, resendThisOffer)
        case MatchedInstanceOps(offerId, tasks, _) => acceptOffer(offerId, tasks)
      }
  }

  private[this] def declineOffer(offerId: OfferID, resendThisOffer: Boolean): Future[Done] = {
    //if the offer should be resent, than we ignore the configured decline offer duration
    val duration: Option[Long] = if (resendThisOffer) None else conf.declineOfferDuration.toOption
    taskLauncher.declineOffer(offerId, duration)
    Future.successful(Done)
  }

  private[this] def acceptOffer(offerId: OfferID, taskOpsWithSource: Seq[InstanceOpWithSource]): Future[Done] = {
    if (taskLauncher.acceptOffer(offerId, taskOpsWithSource.map(_.op))) {
      logger.debug(s"Offer [${offerId.getValue}]. Task launch successful")
      taskOpsWithSource.foreach(_.accept())
      Future.successful(Done)
    } else {
      logger.warn(s"Offer [${offerId.getValue}]. Task launch rejected")
      taskOpsWithSource.foreach(_.reject("driver unavailable"))
      revertTaskOps(taskOpsWithSource.map(_.op)(collection.breakOut))
    }
  }

  /** Revert the effects of the task ops on the task state. */
  private[this] def revertTaskOps(ops: Seq[InstanceOp]): Future[Done] = {
    val done: Future[Done] = Future.successful(Done)
    ops.foldLeft(done) { (terminatedFuture, nextOp) =>
      terminatedFuture.flatMap { _ =>
        nextOp.oldInstance match {
          case Some(existingInstance) =>
            instanceTracker.revert(existingInstance)
          case None =>
            instanceTracker.forceExpunge(nextOp.instanceId)
        }
      }
    }.recover {
      case NonFatal(e) =>
        throw new RuntimeException("while reverting task ops", e)
    }
  }

  /**
    * Saves the given tasks sequentially, evaluating before each save whether the given deadline has been reached
    * already.
    */
  private[this] def saveTasks(
    ops: Seq[InstanceOpWithSource]): Future[Seq[InstanceOpWithSource]] = {

    def saveTask(taskOpWithSource: InstanceOpWithSource): Future[Option[InstanceOpWithSource]] = {
      val taskId = taskOpWithSource.instanceId
      logger.info(s"Processing ${taskOpWithSource.op.stateOp} for ${taskOpWithSource.instanceId}")
      instanceTracker
        .process(taskOpWithSource.op.stateOp)
        .map(_ => Some(taskOpWithSource))
        .recoverWith {
          case NonFatal(e) =>
            oldSavingTasksErrorMetric.increment()
            newSavingTasksErrorMetric.increment()
            taskOpWithSource.reject(s"storage error: $e")
            logger.warn(s"error while storing task $taskId for app [${taskId.runSpecId}]", e)
            revertTaskOps(Seq(taskOpWithSource.op))
        }.map { _ => Some(taskOpWithSource) }
    }

    ops.foldLeft(Future.successful(Vector.empty[InstanceOpWithSource])) { (savedTasksFuture, nextTask) =>
      savedTasksFuture.flatMap { savedTasks =>
        val saveTaskFuture = saveTask(nextTask)
        saveTaskFuture.map(task => savedTasks ++ task)
      }
    }
  }

}
