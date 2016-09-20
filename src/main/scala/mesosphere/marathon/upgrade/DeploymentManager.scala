package mesosphere.marathon.upgrade

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerActor.{ RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ Group, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import mesosphere.marathon.{ ConcurrentTaskUpgradeException, DeploymentCanceledException, SchedulerActions }
import org.apache.mesos.SchedulerDriver

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class DeploymentManager(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor) extends Actor with ActorLogging {
  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentManager._

  val runningDeployments: mutable.Map[String, DeploymentInfo] = mutable.Map.empty[String, DeploymentInfo]
  val deploymentStatus: mutable.Map[String, DeploymentStepInfo] = mutable.Map.empty[String, DeploymentStepInfo]

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) => Stop
  }

  def receive: Receive = {
    case CancelConflictingDeployments(plan) =>
      val conflictingDeployments = for {
        info <- runningDeployments.values
        if info.plan.isAffectedBy(plan)
      } yield info

      val cancellations = conflictingDeployments.map { info =>
        stopActor(info.ref, new DeploymentCanceledException("The upgrade has been cancelled"))
      }

      Future.sequence(cancellations).onComplete { _ =>
        log.info(s"Conflicting deployments for deployment ${plan.id} have been canceled")
        scheduler.schedulerActor ! ConflictingDeploymentsCanceled(
          plan.id,
          if (conflictingDeployments.nonEmpty) {
            conflictingDeployments.map(_.plan).to[Seq]
          } else Seq(plan))
      }

    case StopAllDeployments =>
      for ((_, DeploymentInfo(ref, _)) <- runningDeployments) context.stop(ref)
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
        DeploymentActor.props(
          self,
          sender(),
          driver,
          killService,
          scheduler,
          plan,
          taskTracker,
          launchQueue,
          storage,
          healthCheckManager,
          eventBus,
          readinessCheckExecutor
        ),
        plan.id
      )
      runningDeployments += plan.id -> DeploymentInfo(ref, plan)

    case stepInfo: DeploymentStepInfo => deploymentStatus += stepInfo.plan.id -> stepInfo

    case ReadinessCheckUpdate(id, result) => deploymentStatus.get(id).foreach { info =>
      deploymentStatus += id -> info.copy(readinessChecks = info.readinessChecks.updated(result.taskId, result))
    }

    case _: PerformDeployment =>
      sender() ! Status.Failure(new ConcurrentTaskUpgradeException("Deployment is already in progress"))

    case RetrieveRunningDeployments =>
      sender() ! RunningDeployments(deploymentStatus.values.to[Seq])
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }
}

object DeploymentManager {
  case class PerformDeployment(driver: SchedulerDriver, plan: DeploymentPlan)
  case class CancelDeployment(id: String)
  case object StopAllDeployments
  case class CancelConflictingDeployments(plan: DeploymentPlan)

  case class DeploymentStepInfo(
      plan: DeploymentPlan,
      step: DeploymentStep,
      nr: Int,
      readinessChecks: Map[Task.Id, ReadinessCheckResult] = Map.empty) {
    lazy val readinessChecksByApp: Map[PathId, Iterable[ReadinessCheckResult]] = {
      readinessChecks.values.groupBy(_.taskId.runSpecId).withDefaultValue(Iterable.empty)
    }
  }

  case class DeploymentFinished(plan: DeploymentPlan)
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable)
  case class AllDeploymentsCanceled(plans: Seq[DeploymentPlan])
  case class ConflictingDeploymentsCanceled(id: String, deployments: Seq[DeploymentPlan])
  case class ReadinessCheckUpdate(deploymentId: String, result: ReadinessCheckResult)

  case class DeploymentInfo(
    ref: ActorRef,
    plan: DeploymentPlan)

  def props(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor): Props = {
    Props(new DeploymentManager(taskTracker, killService, launchQueue,
      scheduler, storage, healthCheckManager, eventBus, readinessCheckExecutor))
  }

}
