package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment._
import mesosphere.marathon.core.deployment.impl.DeploymentActor.{ Cancel, Fail, NextStep }
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.DeploymentFinished
import mesosphere.marathon.core.event.{ DeploymentStatus, DeploymentStepFailure, DeploymentStepSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, RunSpec }
import mesosphere.mesos.Constraints

import scala.async.Async._
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

private class DeploymentActor(
    deploymentManager: ActorRef,
    promise: Promise[Done],
    killService: KillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor) extends Actor with StrictLogging {

  import context.dispatcher

  val steps = plan.steps.iterator
  var currentStepNr: Int = 0

  override def preStart(): Unit = {
    self ! NextStep
  }

  override def postStop(): Unit = {
    deploymentManager ! DeploymentFinished(plan)
  }

  def receive: Receive = {
    case NextStep if steps.hasNext =>
      val step = steps.next()
      currentStepNr += 1
      logger.debug(s"Process next deployment step: stepNumber=$currentStepNr step=$step planId=${plan.id}")
      deploymentManager ! DeploymentStepInfo(plan, step, currentStepNr)

      performStep(step) onComplete {
        case Success(_) => self ! NextStep
        case Failure(t) => self ! Fail(t)
      }

    case NextStep =>
      // no more steps, we're done
      logger.debug(s"No more deployment steps to process: planId=${plan.id}")
      promise.success(Done)
      context.stop(self)

    case Cancel(t) =>
      promise.failure(t)
      context.stop(self)

    case Fail(t) =>
      logger.debug(s"Deployment failed: planId=${plan.id}", t)
      promise.failure(t)
      context.stop(self)
  }

  // scalastyle:off
  def performStep(step: DeploymentStep): Future[Unit] = {
    logger.debug(s"Perform deployment step: step=$step planId=${plan.id}")
    if (step.actions.isEmpty) {
      Future.successful(())
    } else {
      val status = DeploymentStatus(plan, step)
      eventBus.publish(status)

      val futures = step.actions.map { action =>
        action.runSpec match {
          case app: AppDefinition => healthCheckManager.addAllFor(app, Seq.empty)
          case pod: PodDefinition => //ignore: no marathon based health check for pods
        }
        action match {
          case StartApplication(run, scaleTo) => startRunnable(run, scaleTo, status)
          case ScaleApplication(run, scaleTo, toKill) => scaleRunnable(run, scaleTo, toKill, status)
          case RestartApplication(run) => restartRunnable(run, status)
          case StopApplication(run) => stopRunnable(run.withInstances(0))
        }
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) =>
          logger.debug(s"Deployment step successful: step=$step plandId=${plan.id}")
          eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(e) =>
          logger.debug(s"Deployment step failed: step=$step plandId=${plan.id}", e)
          eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }

  // scalastyle:on

  def startRunnable(runnableSpec: RunSpec, scaleTo: Int, status: DeploymentStatus): Future[Unit] = {
    val promise = Promise[Unit]()
    instanceTracker.specInstances(runnableSpec.id).map { instances =>
      context.actorOf(AppStartActor.props(deploymentManager, status, scheduler, launchQueue, instanceTracker,
        eventBus, readinessCheckExecutor, runnableSpec, scaleTo, instances, promise))
    }
    promise.future
  }

  @SuppressWarnings(Array("all")) /* async/await */
  def scaleRunnable(runnableSpec: RunSpec, scaleTo: Int,
    toKill: Option[Seq[Instance]],
    status: DeploymentStatus): Future[Unit] = {
    logger.debug("Scale runnable {}", runnableSpec)

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runnableSpec, notSentencedAndRunning, toKillCount)
    }

    async {
      val instances = await(instanceTracker.specInstances(runnableSpec.id))
      val runningInstances = instances.filter(_.state.condition.isActive)
      val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
        runningInstances, toKill, killToMeetConstraints, scaleTo, runnableSpec.killSelection)

      def killTasksIfNeeded: Future[Unit] = {
        logger.debug("Kill tasks if needed")
        tasksToKill.fold(Future.successful(())) { tasks =>
          logger.debug("Kill tasks {}", tasks)
          killService.killInstances(tasks, KillReason.DeploymentScaling).map(_ => ())
        }
      }
      await(killTasksIfNeeded)

      def startTasksIfNeeded: Future[Unit] = {
        tasksToStart.fold(Future.successful(())) { tasksToStart =>
          logger.debug(s"Start next $tasksToStart tasks")
          val promise = Promise[Unit]()
          context.actorOf(TaskStartActor.props(deploymentManager, status, scheduler, launchQueue, instanceTracker, eventBus,
            readinessCheckExecutor, runnableSpec, scaleTo, promise))
          promise.future
        }
      }
      await(startTasksIfNeeded)
    }
  }

  @SuppressWarnings(Array("all")) /* async/await */
  def stopRunnable(runnableSpec: RunSpec): Future[Unit] = async {
    val instances = await(instanceTracker.specInstances(runnableSpec.id))
    val launchedInstances = instances.filter(_.isLaunched)
    // TODO: the launch queue is purged in stopRunnable, but it would make sense to do that before calling kill(tasks)
    await(killService.killInstances(launchedInstances, KillReason.DeletingApp))
    scheduler.stopRunSpec(runnableSpec)
  }

  def restartRunnable(run: RunSpec, status: DeploymentStatus): Future[Unit] = {
    if (run.instances == 0) {
      Future.successful(())
    } else {
      val promise = Promise[Unit]()
      context.actorOf(TaskReplaceActor.props(deploymentManager, status, killService,
        launchQueue, instanceTracker, eventBus, readinessCheckExecutor, run, promise))
      promise.future
    }
  }
}

object DeploymentActor {
  case object NextStep
  case object Finished
  case class Cancel(reason: Throwable)
  case class Fail(reason: Throwable)
  case class DeploymentActionInfo(plan: DeploymentPlan, step: DeploymentStep, action: DeploymentAction)

  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    promise: Promise[Done],
    killService: KillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor): Props = {

    Props(new DeploymentActor(
      deploymentManager,
      promise,
      killService,
      scheduler,
      plan,
      taskTracker,
      launchQueue,
      healthCheckManager,
      eventBus,
      readinessCheckExecutor
    ))
  }
}
