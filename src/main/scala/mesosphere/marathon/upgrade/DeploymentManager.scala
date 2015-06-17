package mesosphere.marathon.upgrade

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerActor.{ RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ AppRepository, Group, Timestamp }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.{ ConcurrentTaskUpgradeException, DeploymentCanceledException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class DeploymentManager(
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: LaunchQueue,
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

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def receive: Receive = {
    case CancelConflictingDeployments(plan) =>
      val conflictingDeployments = for {
        info <- runningDeployments.values
        if info.plan.isAffectedBy(plan)
      } yield info

      val cancellations = conflictingDeployments.map { info =>
        stopActor(info.ref, new DeploymentCanceledException("The upgrade has been cancelled"))
      }

      Future.sequence(cancellations) onComplete {
        case _ =>
          log.info(s"Conflicting deployments for deployment ${plan.id} have been canceled")
          scheduler.schedulerActor ! ConflictingDeploymentsCanceled(
            plan.id,
            if (conflictingDeployments.nonEmpty) {
              conflictingDeployments.map(_.plan).to[Seq]
            }
            else Seq(plan))
      }

    case CancelAllDeployments =>
      for ((_, DeploymentInfo(ref, _)) <- runningDeployments)
        ref ! Cancel(new DeploymentCanceledException("The upgrade has been cancelled"))
      runningDeployments.clear()
      deploymentStatus.clear()

    case CancelDeployment(id) =>
      val origSender = sender()

      runningDeployments.get(id) match {
        case Some(info) =>
          info.ref ! Cancel(new DeploymentCanceledException("The upgrade has been cancelled"))
        case None =>
          origSender ! DeploymentFailed(
            DeploymentPlan(id, Group.empty, Group.empty, Nil, Timestamp.now()),
            new DeploymentCanceledException("The upgrade has been cancelled"))
      }

    case msg @ DeploymentFinished(plan) =>
      log.info(s"Removing ${plan.id} from list of running deployments")
      runningDeployments -= plan.id
      deploymentStatus -= plan.id

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
  final case class CancelDeployment(id: String)
  case object CancelAllDeployments
  final case class CancelConflictingDeployments(plan: DeploymentPlan)

  final case class DeploymentStepInfo(plan: DeploymentPlan, step: DeploymentStep, nr: Int)
  final case class DeploymentFinished(plan: DeploymentPlan)
  final case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable)
  final case class AllDeploymentsCanceled(plans: Seq[DeploymentPlan])
  final case class ConflictingDeploymentsCanceled(id: String, deployments: Seq[DeploymentPlan])

  final case class DeploymentInfo(
    ref: ActorRef,
    plan: DeploymentPlan)
}
