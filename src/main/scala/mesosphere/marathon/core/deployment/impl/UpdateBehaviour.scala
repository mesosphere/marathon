package mesosphere.marathon
package core.deployment.impl

import akka.pattern._
import akka.actor.{Actor, Stash}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.{DeploymentStatus, InstanceChanged, InstanceHealthChanged}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, RunSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

/**
  * A [[FrameProcessor]] handles the next [[Frame]] that is passed by the [[UpdateBehaviour]].
  */
trait FrameProcessor extends StrictLogging {

  val status: DeploymentStatus
  val runSpec: RunSpec
  lazy val pathId: PathId = runSpec.id

  /**
    * Make a business decision based on the passed frame.
    *
    * @param completedPhases Denotes the updates the [[UpdateBehaviour]] has seen. 0 means this is this initialization.
    * @param frame           The [[UpdateBehaviour.currentFrame]].
    * @return [[FrameProcessor.Stop]] is we are done deploying or [[FrameProcessor.Continue]] with an updated [[Frame]].
    */
  def process(completedPhases: Int, frame: Frame): FrameProcessor.ProcessResult

  def logPrefixedInfo(phase: String)(msg: String): Unit = logger.info(s"Deployment ${status.plan.id} Phase $phase: $msg")

  def readableInstanceString(instance: Instance): String =
    s"Instance(id=${instance.instanceId}, version=${instance.runSpecVersion}, goal=${instance.state.goal}, condition=${instance.state.condition})"
}

object FrameProcessor {

  case object Process
  case object FinishedApplyingOperations

  /**
    * Result of [[FrameProcessor.process()]].
    */
  sealed trait ProcessResult

  /**
    * The deployment is done. Continue with the new frame and wait for new updates.
    *
    * @param nextFrame The frame updated by [[FrameProcessor.process()]]. It includes changed goals and scheduled instances.
    */
  case class Continue(nextFrame: Frame) extends ProcessResult

  /**
    * The deployment is done.
    */
  case object Stop extends ProcessResult

}

/**
  * The [[UpdateBehaviour]] accumulates an internal [[Frame]], called [[UpdateBehaviour.currentFrame]], based on updates
  * and passes it on to the [[FrameProcessor]].
  *
  * The updates can be [[InstanceChanged]], [[InstanceHealthChanged]] and [[mesosphere.marathon.core.readiness.ReadinessCheckResult]]
  * events.
  *
  * This handling cycles through the following phases:
  *
  * 0. `initialize` load initial state
  * 1. `processing` make business decisions based on the new state of the instances. The logic is defined in the FrameProcessor.
  * 2. `updating` handle instance updates and apply them the current frame.
  */
trait UpdateBehaviour extends ReadinessBehaviour with Stash { this: Actor with FrameProcessor =>

  var currentFrame: Frame
  var completedPhases: Int = 0

  /**
    * The promise that is completed when [[process()]] returns [[FrameProcessor.Stop]].
    */
  val promise: Promise[Unit]

  val instanceTracker: InstanceTracker
  val eventBus: EventStream

  override def preStart(): Unit = {
    if (runSpec.hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    self ! FrameProcessor.Process
  }

  override def receive: Receive = processing

  /**
    * Apply an event to the [[currentFrame]] and pass it on to the [[FrameProcessor]], ie business logic.
    */
  def updating: Receive = (instanceChangeUpdates orElse readinessUpdates).andThen { _ =>
    context.become(processing)
    self ! FrameProcessor.Process
  }

  /**
    * Handle [[InstanceHealthChanged]] and [[InstanceChanged]] events.
    */
  val instanceChangeUpdates: Receive = {
    case InstanceChanged(id, _, _, _, inst) =>
      logPrefixedInfo("updating")(s"Received update for ${readableInstanceString(inst)}")
      // Update all instances.
      currentFrame = currentFrame.copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))

    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logPrefixedInfo("updating")(s"Received health update for $id: $healthy")
      currentFrame = currentFrame
        .copy(instances = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut))
        .updateHealth(id, healthy.getOrElse(false))
  }

  /**
    * Pass the updated [[currentFrame]] to [[process()]] of the [[FrameProcessor]].
    */
  def processing: Receive = {
    case FrameProcessor.Process =>
      process(completedPhases, currentFrame) match {
        case FrameProcessor.Continue(nextFrame) =>
          logPrefixedInfo("processing")("Continue handling updates")

          // Replicate state in instance tracker by replaying operations.
          Future.sequence(nextFrame.operations.map { op => instanceTracker.process(op) })
            .map(_ => FrameProcessor.FinishedApplyingOperations)
            .pipeTo(self)

          // Update our internal state.
          currentFrame = nextFrame.withoutOperations()

        case FrameProcessor.Stop =>
          logPrefixedInfo("processing")("We are done. Stopping.")
          promise.trySuccess(())
          context.stop(self)
      }
      completedPhases += 1

    case FrameProcessor.FinishedApplyingOperations =>
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
