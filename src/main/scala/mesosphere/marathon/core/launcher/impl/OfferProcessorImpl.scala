package mesosphere.marathon
package core.launcher.impl

import java.time.Clock

import akka.Done
import akka.stream.scaladsl.SourceQueue
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.{ InstanceOp, OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.task.tracker.InstanceCreationHandler
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric }
import org.apache.mesos.Protos.{ Offer, OfferID }

import scala.concurrent.Future
import scala.util.control.NonFatal

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
    conf: OfferProcessorConfig, clock: Clock,
    offerMatcher: OfferMatcher,
    taskLauncher: TaskLauncher,
    taskCreationHandler: InstanceCreationHandler,
    offerStreamInput: SourceQueue[Offer]) extends OfferProcessor with StrictLogging {
  import mesosphere.marathon.core.async.ExecutionContexts.global

  private[this] val incomingOffersMeter =
    Metrics.minMaxCounter(ServiceMetric, getClass, "incomingOffers")
  private[this] val matchTimeMeter =
    Metrics.timer(ServiceMetric, getClass, "matchTime")
  private[this] val matchErrorsMeter =
    Metrics.minMaxCounter(ServiceMetric, getClass, "matchErrors")
  private[this] val savingTasksTimeMeter =
    Metrics.timer(ServiceMetric, getClass, "savingTasks")
  private[this] val savingTasksErrorMeter =
    Metrics.minMaxCounter(ServiceMetric, getClass, "savingTasksErrors")

  override def processOffer(offer: Offer): Future[Done] = {
    incomingOffersMeter.increment()
    offerStreamInput.offer(offer)

    val matchFuture: Future[MatchedInstanceOps] = matchTimeMeter {
      offerMatcher.matchOffer(offer)
    }

    matchFuture
      .recover {
        case NonFatal(e) =>
          matchErrorsMeter.increment()
          logger.error(s"Could not process offer '${offer.getId.getValue}'", e)
          MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = true)
      }.flatMap {
        case MatchedInstanceOps(offerId, tasks, resendThisOffer) =>
          savingTasksTimeMeter {
            saveTasks(tasks).map { savedTasks =>
              def notAllSaved: Boolean = savedTasks.size != tasks.size
              MatchedInstanceOps(offerId, savedTasks, resendThisOffer || notAllSaved)
            }
          }
      }.flatMap {
        case MatchedInstanceOps(offerId, Nil, resendThisOffer) => declineOffer(offerId, resendThisOffer)
        case MatchedInstanceOps(offerId, tasks, _) => acceptOffer(offerId, tasks)
      }
  }

  private[this] def declineOffer(offerId: OfferID, resendThisOffer: Boolean): Future[Done] = {
    //if the offer should be resent, than we ignore the configured decline offer duration
    val duration: Option[Long] = if (resendThisOffer) None else conf.declineOfferDuration.get
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
            taskCreationHandler.created(InstanceUpdateOperation.Revert(existingInstance))
          case None =>
            taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(nextOp.instanceId))
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
      taskCreationHandler
        .created(taskOpWithSource.op.stateOp)
        .map(_ => Some(taskOpWithSource))
        .recoverWith {
          case NonFatal(e) =>
            savingTasksErrorMeter.increment()
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
