package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import mesosphere.marathon.MarathonSchedulerActor.{ RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentActor.RetrieveCurrentStep
import mesosphere.marathon.{ ConcurrentTaskUpgradeException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

class DeploymentManager(
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
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
      val ref = context.actorOf(Props(classOf[DeploymentActor], self, sender, appRepository, driver, scheduler, plan, taskTracker, taskQueue, storage, eventBus))
      runningDeployments += plan.id -> DeploymentInfo(ref, plan)

    case _: PerformDeployment =>
      sender ! Status.Failure(new ConcurrentTaskUpgradeException("Deployment is already in progress"))

    case RetrieveRunningDeployments =>
      implicit val timeout: Timeout = 5.seconds
      val deploymentInfos = runningDeployments.values.toSeq.map { info =>
        val res = (info.ref ? RetrieveCurrentStep).mapTo[DeploymentStep]
        res.map(info.plan -> _)
      }

      Future.sequence(deploymentInfos).map(RunningDeployments).pipeTo(sender)
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }
}

object DeploymentManager {
  final case class PerformDeployment(driver: SchedulerDriver, plan: DeploymentPlan)
  final case class CancelDeployment(id: String, reason: Throwable)
  final case class CancelConflictingDeployments(plan: DeploymentPlan, reason: Throwable)

  final case class DeploymentFinished(id: String)
  final case class DeploymentCanceled(id: String)
  final case class ConflictingDeploymentsCanceled(id: String)

  final case class DeploymentInfo(
    ref: ActorRef,
    plan: DeploymentPlan)
}
