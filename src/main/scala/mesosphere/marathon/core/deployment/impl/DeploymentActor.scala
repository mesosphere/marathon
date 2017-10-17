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
import scala.util.control.NonFatal
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

  // Default supervision strategy is overridden here to restart deployment child actors (responsible for individual
  // deployment steps e.g. AppStartActor, TaskStartActor etc.) even if any exception occurs (even during initialisation).
  // This is due to the fact that child actors tend to gather information during preStart about the tasks that are
  // already running from the TaskTracker and LaunchQueue and those calls can timeout. In general deployment child
  // actors are built idempotent which should make restarting them possible.
  // Additionally a BackOffSupervisor is used to make sure child actor failures are not overloading other parts of the system
  // (like LaunchQueue and InstanceTracker) and are not filling the log with exceptions.
  import scala.concurrent.duration._
  import akka.pattern.{ Backoff, BackoffSupervisor }

  def childSupervisor(props: Props, name: String): Props = {
    BackoffSupervisor.props(
      Backoff.onFailure(
        childProps = props,
        childName = name,
        minBackoff = 5.seconds,
        maxBackoff = 1.minute,
        randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
      ).withSupervisorStrategy(
        OneForOneStrategy() {
          case NonFatal(_) => SupervisorStrategy.Restart
          case _ => SupervisorStrategy.Escalate
        }
      ))
  }

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
      context.actorOf(childSupervisor(AppStartActor.props(deploymentManager, status, scheduler, launchQueue, instanceTracker,
        eventBus, readinessCheckExecutor, runnableSpec, scaleTo, instances, promise), s"AppStart-${plan.id}"))
    }
    promise.future
  }

  @SuppressWarnings(Array("all")) /* async/await */
  def scaleRunnable(runnableSpec: RunSpec, scaleTo: Int,
    toKill: Option[Seq[Instance]],
    status: DeploymentStatus): Future[Done] = {
    logger.debug(s"Scale runnable $runnableSpec")

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runnableSpec, notSentencedAndRunning, toKillCount)
    }

    async {
      val instances = await(instanceTracker.specInstances(runnableSpec.id))
      val runningInstances = instances.filter(_.state.condition.isActive)
      val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
        runningInstances, toKill, killToMeetConstraints, scaleTo, runnableSpec.killSelection)

      def killTasksIfNeeded: Future[Done] = {
        logger.debug("Kill tasks if needed")
        tasksToKill.fold(Future.successful(Done)) { tasks =>
          logger.debug("Kill tasks {}", tasks)
          killService.killInstances(tasks, KillReason.DeploymentScaling).map(_ => Done)
        }
      }
      await(killTasksIfNeeded)

      def startTasksIfNeeded: Future[Done] = {
        tasksToStart.fold(Future.successful(Done)) { tasksToStart =>
          logger.debug(s"Start next $tasksToStart tasks")
          val promise = Promise[Unit]()
          context.actorOf(childSupervisor(TaskStartActor.props(deploymentManager, status, scheduler, launchQueue, instanceTracker, eventBus,
            readinessCheckExecutor, runnableSpec, scaleTo, promise), s"TaskStart-${plan.id}"))
          promise.future.map(_ => Done)
        }
      }
      await(startTasksIfNeeded)
    }
  }

  @SuppressWarnings(Array("all")) /* async/await */
  def stopRunnable(runnableSpec: RunSpec): Future[Done] = async {
    logger.debug(s"Stop runnable $runnableSpec")
    val instances = await(instanceTracker.specInstances(runnableSpec.id))
    val launchedInstances = instances.filter(_.isLaunched)
    // TODO: the launch queue is purged in stopRunnable, but it would make sense to do that before calling kill(tasks)
    await(killService.killInstances(launchedInstances, KillReason.DeletingApp))

    logger.debug(s"Killed all remaining tasks: ${launchedInstances.map(_.instanceId)}")

    // Note: This is an asynchronous call. We do NOT wait for the run spec to stop. If we do, the DeploymentActorTest
    // fails.
    scheduler.stopRunSpec(runnableSpec)

    Done
  }

  def restartRunnable(run: RunSpec, status: DeploymentStatus): Future[Done] = {
    if (run.instances == 0) {
      Future.successful(Done)
    } else {
      val promise = Promise[Unit]()
      context.actorOf(childSupervisor(TaskReplaceActor.props(deploymentManager, status, killService,
        launchQueue, instanceTracker, eventBus, readinessCheckExecutor, run, promise), s"TaskReplace-${plan.id}"))
      promise.future.map(_ => Done)
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
