package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerActor.{ RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.{ ConcurrentTaskUpgradeException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }

class DeploymentManager(
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    scheduler: SchedulerActions,
    eventBus: EventStream) extends Actor with ActorLogging {
  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentManager._

  val runningDeployments: mutable.Map[String, DeploymentInfo] = mutable.Map.empty[String, DeploymentInfo]

  def receive = {
    case CancelConflictingDeployments(plan, reason) =>
      val origSender = sender
      val conflictingDeployments = for {
        info <- runningDeployments.values
        if info.plan.isAffectedBy(plan)
      } yield info

      val cancellations = conflictingDeployments.map { info =>
        stopActor(info.ref, reason)
      }

      Future.sequence(cancellations) onComplete {
        case _ => origSender ! ConflictingDeploymentsCanceled(plan.id)
      }

    case CancelDeployment(id, t) =>
      val origSender = sender

      runningDeployments.get(id) match {
        case Some(info) =>
          stopActor(info.ref, t) onComplete {
            case _ => origSender ! DeploymentCanceled(id)
          }

        case None =>
          origSender ! DeploymentCanceled(id)
      }

    case DeploymentFinished(id) =>
      log.info(s"Removing $id from list of running deployments")
      runningDeployments -= id

    case PerformDeployment(driver, plan) if !runningDeployments.contains(plan.id) =>
      val ref = context.actorOf(Props(classOf[DeploymentActor], self, sender, appRepository, driver, scheduler, plan, taskTracker, taskQueue, eventBus))
      runningDeployments += plan.id -> DeploymentInfo(ref, plan)

    case _: PerformDeployment =>
      sender ! Status.Failure(new ConcurrentTaskUpgradeException("Deployment is already in progress"))

    case RetrieveRunningDeployments =>
      sender ! RunningDeployments(runningDeployments.values.map(_.plan).toSeq)
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }
}

object DeploymentManager {
  case class PerformDeployment(driver: SchedulerDriver, plan: DeploymentPlan)
  case class CancelDeployment(id: String, reason: Throwable)
  case class CancelConflictingDeployments(plan: DeploymentPlan, reason: Throwable)

  case class DeploymentFinished(id: String)
  case class DeploymentCanceled(id: String)
  case class ConflictingDeploymentsCanceled(id: String)

  case class DeploymentInfo(
    ref: ActorRef,
    plan: DeploymentPlan)
}
