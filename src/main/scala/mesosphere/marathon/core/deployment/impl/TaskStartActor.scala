package mesosphere.marathon
package core.deployment.impl

import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.instance.Goal
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.concurrent.Promise

/**
  * The [[TaskStartBehaviour]] defined the business logic of the task starting of a deployment.
  */
trait TaskStartBehaviour extends FrameProcessor with StrictLogging { this: BaseReadinessScheduling =>

  val scaleTo = runSpec.instances

  /**
    * Process the [[UpdateBehaviour.currentFrame]].
    *
    * @param completedPhases Denotes the updates the [[UpdateBehaviour]] has seen. 0 means this is this initialization.
    * @param frame           The [[UpdateBehaviour.currentFrame]].
    * @return [[FrameProcessor.Stop]] is we are done deploying or [[FrameProcessor.Continue]] with an updated [[Frame]].
    */
  override def process(completedPhases: Int, frame: Frame): FrameProcessor.ProcessResult = {
    if (check(frame)) {
      //logger.info(s"Successfully started $nrToStart instances of ${runSpec.id}")
      FrameProcessor.Stop
    } else {
      if (completedPhases == 0) {
        FrameProcessor.Continue(initializeStart(frame))
      } else {
        FrameProcessor.Continue(scheduleReadinessCheck(frame))
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
      if (runSpec.hasHealthChecks) frame.instancesHealth.getOrElse(instance.instanceId, false) else true
    }
    val ready = frame.instances.valuesIterator.count { instance =>
      if (runSpec.hasReadinessChecks) frame.instancesReady.getOrElse(instance.instanceId, false) else true
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
}

class TaskStartActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val promise: Promise[Unit]) extends Actor with TaskStartBehaviour with UpdateBehaviour with StrictLogging {

  // All running instances of this app
  var currentFrame = Frame(instanceTracker.specInstancesSync(runSpec.id))

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
