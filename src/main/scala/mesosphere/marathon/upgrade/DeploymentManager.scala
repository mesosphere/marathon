package mesosphere.marathon.upgrade

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerActor.{ RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.{ ConcurrentTaskUpgradeException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class DeploymentManager(
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream) extends Actor with ActorLogging {
  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentManager._

  val runningDeployments: mutable.Map[String, DeploymentInfo] = mutable.Map.empty[String, DeploymentInfo]
  val deploymentStatus: mutable.Map[String, DeploymentStepInfo] = mutable.Map.empty[String, DeploymentStepInfo]

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) => Stop
  }

  def receive: Receive = {
    case CancelConflictingDeployments(plan, reason) =>
      val origSender = sender()
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
      val origSender = sender()

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
      deploymentStatus -= id

    case PerformDeployment(driver, plan) if !runningDeployments.contains(plan.id) =>
      val ref = context.actorOf(
        Props(
          classOf[DeploymentActor],
          self,
          sender(),
          appRepository,
          driver,
          scheduler,
          plan,
          taskTracker,
          taskQueue,
          storage,
          healthCheckManager,
          eventBus
        ),
        plan.id
      )
      runningDeployments += plan.id -> DeploymentInfo(ref, plan)

    case stepInfo: DeploymentStepInfo => deploymentStatus += stepInfo.plan.id -> stepInfo

    case _: PerformDeployment =>
      sender() ! Status.Failure(new ConcurrentTaskUpgradeException("Deployment is already in progress"))

    case RetrieveRunningDeployments =>
      val deployments: Iterable[(DeploymentPlan, DeploymentStepInfo)] =
        deploymentStatus.values.map(step => step.plan -> step)
      sender() ! RunningDeployments(deployments.to[Seq])
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

  final case class DeploymentStepInfo(plan: DeploymentPlan, step: DeploymentStep, nr: Int)
  final case class DeploymentFinished(id: String)
  final case class DeploymentCanceled(id: String)
  final case class ConflictingDeploymentsCanceled(id: String)

  final case class DeploymentInfo(
    ref: ActorRef,
    plan: DeploymentPlan)
}
