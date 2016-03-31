package mesosphere.marathon.core.launcher.impl

import akka.pattern.AskTimeoutException
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.{ TaskOp, OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpWithSource }
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.tracker.TaskCreationHandler
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ Offer, OfferID }
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
    offerMatcher: OfferMatcher,
    taskLauncher: TaskLauncher,
    taskCreationHandler: TaskCreationHandler) extends OfferProcessor {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val offerMatchingTimeout = conf.offerMatchingTimeout().millis
  private[this] val saveTasksToLaunchTimeout = conf.saveTasksToLaunchTimeout().millis

  private[this] val incomingOffersMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "incomingOffers"))
  private[this] val matchTimeMeter =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "matchTime"))
  private[this] val matchErrorsMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "matchErrors"))
  private[this] val savingTasksTimeMeter =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "savingTasks"))
  private[this] val savingTasksTimeoutMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "savingTasksTimeouts"))
  private[this] val savingTasksErrorMeter =
    metrics.meter(metrics.name(MetricPrefixes.SERVICE, getClass, "savingTasksErrors"))

  override def processOffer(offer: Offer): Future[Unit] = {
    incomingOffersMeter.mark()

    val matchingDeadline = clock.now() + offerMatchingTimeout
    val savingDeadline = matchingDeadline + saveTasksToLaunchTimeout

    val matchFuture: Future[MatchedTaskOps] = matchTimeMeter.timeFuture {
      offerMatcher.matchOffer(matchingDeadline, offer)
    }

    matchFuture
      .recover {
        case e: AskTimeoutException =>
          matchErrorsMeter.mark()
          log.warn(s"Could not process offer '${offer.getId.getValue}' in time. (See --max_offer_matching_timeout)")
          MatchedTaskOps(offer.getId, Seq.empty, resendThisOffer = true)
        case NonFatal(e) =>
          matchErrorsMeter.mark()
          log.error(s"Could not process offer '${offer.getId.getValue}'", e)
          MatchedTaskOps(offer.getId, Seq.empty, resendThisOffer = true)
      }.flatMap {
        case MatchedTaskOps(offerId, tasks, resendThisOffer) =>
          savingTasksTimeMeter.timeFuture {
            saveTasks(tasks, savingDeadline).map { savedTasks =>
              def notAllSaved: Boolean = savedTasks.size != tasks.size
              MatchedTaskOps(offerId, savedTasks, resendThisOffer || notAllSaved)
            }
          }
      }.flatMap {
        case MatchedTaskOps(offerId, Nil, resendThisOffer) => declineOffer(offerId, resendThisOffer)
        case MatchedTaskOps(offerId, tasks, _)             => acceptOffer(offerId, tasks)
      }
  }

  private[this] def declineOffer(offerId: OfferID, resendThisOffer: Boolean): Future[Unit] = {
    //if the offer should be resent, than we ignore the configured decline offer duration
    val duration: Option[Long] = if (resendThisOffer) None else conf.declineOfferDuration.get
    taskLauncher.declineOffer(offerId, duration)
    Future.successful(())
  }

  private[this] def acceptOffer(offerId: OfferID, taskOpsWithSource: Seq[TaskOpWithSource]): Future[Unit] = {
    if (taskLauncher.acceptOffer(offerId, taskOpsWithSource.map(_.op))) {
      log.debug("Offer [{}]. Task launch successful", offerId.getValue)
      taskOpsWithSource.foreach(_.accept())
      Future.successful(())
    }
    else {
      log.warn("Offer [{}]. Task launch rejected", offerId.getValue)
      taskOpsWithSource.foreach(_.reject("driver unavailable"))
      revertTaskOps(taskOpsWithSource.view.map(_.op))
    }
  }

  /** Revert the effects of the task ops on the task state. */
  private[this] def revertTaskOps(ops: Iterable[TaskOp]): Future[Unit] = {
    ops.foldLeft(Future.successful(())) { (terminatedFuture, nextOp) =>
      terminatedFuture.flatMap { _ =>
        nextOp.oldTask match {
          case Some(existingTask) =>
            taskCreationHandler.created(TaskStateOp.Revert(existingTask)).map(_ => ())
          case None =>
            taskCreationHandler.terminated(TaskStateOp.ForceExpunge(nextOp.taskId)).map(_ => ())
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
  private[this] def saveTasks(ops: Seq[TaskOpWithSource], savingDeadline: Timestamp): Future[Seq[TaskOpWithSource]] = {
    def saveTask(taskOpWithSource: TaskOpWithSource): Future[Option[TaskOpWithSource]] = {
      val taskId = taskOpWithSource.taskId
      log.info(s"Processing ${taskOpWithSource.op.stateOp} for ${taskOpWithSource.taskId}")
      taskCreationHandler
        .created(taskOpWithSource.op.stateOp)
        .map(_ => Some(taskOpWithSource))
        .recoverWith {
          case NonFatal(e) =>
            savingTasksErrorMeter.mark()
            taskOpWithSource.reject(s"storage error: $e")
            log.warn(s"error while storing task $taskId for app [${taskId.appId}]", e)
            revertTaskOps(Some(taskOpWithSource.op))
        }.map {
          case Some(savedTask) => Some(taskOpWithSource)
          case None            => None
        }
    }

    ops.foldLeft(Future.successful(Vector.empty[TaskOpWithSource])) { (savedTasksFuture, nextTask) =>
      savedTasksFuture.flatMap { savedTasks =>
        if (clock.now() > savingDeadline) {
          savingTasksTimeoutMeter.mark(savedTasks.size.toLong)
          nextTask.reject("saving timeout reached")
          log.info(
            s"Timeout reached, skipping launch and save for ${nextTask.op.taskId}. " +
              s"You can reconfigure this with --${conf.saveTasksToLaunchTimeout.name}.")
          Future.successful(savedTasks)
        }
        else {
          val saveTaskFuture = saveTask(nextTask)
          saveTaskFuture.map(task => savedTasks ++ task)
        }
      }
    }
  }

}
