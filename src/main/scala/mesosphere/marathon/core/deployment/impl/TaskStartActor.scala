package mesosphere.marathon
package core.deployment.impl

import akka.pattern._
import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{DeploymentStatus, InstanceChanged, InstanceHealthChanged}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import mesosphere.marathon.core.pod.PodDefinition

trait TaskStartActorLogic extends StrictLogging {

  val status: DeploymentStatus
  val runSpec: RunSpec
  val scaleTo = runSpec.instances
  val pathId: PathId = runSpec.id

  def process(completedPhases: Int, frame: Frame): TaskReplaceActor.ProcessResult = {
    if (check(frame)) {
      //logger.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
      TaskReplaceActor.Stop
    } else {
      if (completedPhases == 0) {
        TaskReplaceActor.Continue(initializeStart(frame))
      } else {
        TaskReplaceActor.Continue(scheduleReadinessCheck(frame))
      }
    }
  }

  /**
    * Check if we are done.
    * @param frame The current frame.
    * @return True if the deployment is done. False otherwise.
    */
  def check(frame: Frame): Boolean = {

    // Are all new instances running, ready and healthy?
    val active = frame.instances.valuesIterator.count { instance =>
      instance.state.condition == Condition.Running && instance.state.goal == Goal.Running
    }

    val scheduled = frame.instances.valuesIterator.count { _.isScheduled }
    val healthy = frame.instances.valuesIterator.count { instance =>
      if (hasHealthChecks) frame.instancesHealth.getOrElse(instance.instanceId, false) else true
    }
    val ready = frame.instances.valuesIterator.count { instance =>
      if (hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
    }

    val summary = s"$active active, $scheduled scheduled, $healthy healthy, $ready ready, ${runSpec.instances} target"
    if (active == runSpec.instances && healthy == active && ready == active) {
      logger.info(s"Done for $pathId: $summary")
      true
    } else {
      logger.info(s"Not done yet for $pathId: $summary")
      false
    }
  }

  def nrToStart(frame: Frame): Int = {
    val alreadyLaunched = frame.instances.valuesIterator.count { i => i.isActive || i.isScheduled }
    val target = Math.max(0, scaleTo - alreadyLaunched)
    logger.info(s"TaskStartActor about to start $target instances. $alreadyLaunched already launched, $scaleTo is target count")
    target
  }

  def initializeStart(frame: Frame): Frame = {
    val toStart = nrToStart(frame)
    logger.info(s"TaskStartActor: initializing for ${runSpec.id} and toStart: $toStart")
    if (toStart > 0) frame.add(runSpec, toStart)
    else frame
  }

  protected val hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }

  val hasReadinessChecks: Boolean
  def initiateReadinessCheck(instance: Instance): Unit
  def scheduleReadinessCheck(frame: Frame): Frame

  def logPrefixedInfo(phase: String)(msg: String): Unit = logger.info(s"Deployment ${status.plan.id} Phase $phase: $msg")

  def readableInstanceString(instance: Instance): String =
    s"Instance(id=${instance.instanceId}, version=${instance.runSpecVersion}, goal=${instance.state.goal}, condition=${instance.state.condition})"
}

class TaskStartActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with TaskStartActorLogic with NewReadinessBehaviour with StrictLogging {

  // All running instances of this app
  var currentFrame = Frame(instanceTracker.specInstancesSync(runSpec.id))
  var completedPhases: Int = 0

  override def preStart(): Unit = {
    if (hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    self ! TaskReplaceActor.Process
  }

  override def receive: Receive = processing

  def updating: Receive = (instanceChangeUpdates orElse readinessUpdates).andThen { _ =>
    context.become(processing)
    self ! TaskReplaceActor.Process
  }

  val instanceChangeUpdates: Receive = {
    case InstanceChanged(id, _, _, _, inst) =>
      logPrefixedInfo("updating")(s"Received update for ${readableInstanceString(inst)}")
      // Update all instances.
      currentFrame = currentFrame.copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logPrefixedInfo("updating")(s"Received health update for $id: $healthy")
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      currentFrame = currentFrame
        .copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))
        .updateHealth(id, healthy.getOrElse(false))
  }

  def processing: Receive = {
    case TaskReplaceActor.Process =>
      process(completedPhases, currentFrame) match {
        case TaskReplaceActor.Continue(nextFrame) =>
          logPrefixedInfo("processing")("Continue handling updates")

          // Replicate state in instance tracker by replaying operations.
          Future.sequence(nextFrame.operations.map { op => instanceTracker.process(op) })
            .map(_ => TaskReplaceActor.FinishedApplyingOperations)
            .pipeTo(self)

          // Update our internal state.
          currentFrame = nextFrame.withoutOperations()

        case TaskReplaceActor.Stop =>
          logPrefixedInfo("processing")("We are done. Stopping.")
          promise.trySuccess(())
          context.stop(self)
      }
      completedPhases += 1

    case TaskReplaceActor.FinishedApplyingOperations =>
      logPrefixedInfo("processing")("Finished replicating state to instance tracker.")

      // We went through all phases so lets unleash all pending instance changed updates.
      context.become(updating)
      unstashAll()

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }
}

object TaskStartActor {

  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    promise: Promise[Unit]): Props = {
    Props(new TaskStartActor(deploymentManager, status, launchQueue, instanceTracker,
      eventBus, readinessCheckExecutor, runSpec, promise)
    )
  }
}
