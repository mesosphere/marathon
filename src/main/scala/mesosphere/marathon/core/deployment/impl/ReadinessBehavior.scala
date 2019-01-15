package mesosphere.marathon
package core.deployment.impl

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.ReadinessCheckUpdate
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec

/**
  * ReadinessBehavior makes sure all tasks are healthy and ready depending on an app definition.
  * Listens for TaskStatusUpdate events, HealthCheck events and ReadinessCheck events.
  */
trait ReadinessBehaviour extends BaseReadinessScheduling with StrictLogging { this: Actor =>

  implicit private val materializer = ActorMaterializer()
  import ReadinessBehavior._

  //dependencies
  val runSpec: RunSpec
  def readinessCheckExecutor: ReadinessCheckExecutor
  def deploymentManagerActor: ActorRef
  def status: DeploymentStatus

  //state managed by this behavior
  var currentFrame: Frame
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Cancellable]
  def subscriptionKeys: Set[ReadinessCheckSubscriptionKey] = subscriptions.keySet

  override def postStop(): Unit = {
    subscriptions.values.foreach(_.cancel())
  }

  private def initiateReadinessCheckForTask(task: Task): Unit = {
    logger.debug(s"Schedule readiness check for task: ${task.taskId}")
    ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task).foreach { spec =>
      val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
      val (subscription, streamDone) = readinessCheckExecutor.execute(spec)
        .toMat(Sink.foreach { result: ReadinessCheckResult => self ! result })(Keep.both)
        .run
      streamDone.onComplete { doneResult =>
        self ! ReadinessCheckStreamDone(subscriptionName, doneResult.failed.toOption)
      }(context.dispatcher)
      subscriptions = subscriptions + (subscriptionName -> subscription)
    }
  }

  override def initiateReadinessCheck(instance: Instance): Unit = {
    instance.tasksMap.foreach {
      case (_, task) => initiateReadinessCheckForTask(task)
    }
  }

  val readinessUpdates: Receive = {
    case result: ReadinessCheckResult =>
      logger.info(s"Received readiness check update for ${result.taskId.instanceId} with ready: ${result.ready}")
      deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
      //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
      currentFrame = currentFrame.updateReadiness(result.taskId.instanceId, result.ready)
      if (result.ready) {
        logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
        unsubscripeReadinessCheck(result)
      }

    case ReadinessCheckStreamDone(subscriptionName, maybeFailure) =>
      maybeFailure.foreach { ex =>
        // We should not ever get here
        logger.error(s"Received an unexpected failure for readiness stream $subscriptionName", ex)
      }
      logger.info(s"Readiness check stream $subscriptionName is done")
      subscriptions -= subscriptionName
  }

  /**
    * Call this method for instances that are already started.
    * This should be necessary only after fail over to reconcile the state from a previous run.
    * It will make sure to wait for health checks and readiness checks to become green.
    * @param instance the instance that has been started.
    */
  def reconcileHealthAndReadinessCheck(instance: Instance, currentFrame: Frame): Frame = {
    initiateReadinessCheck(instance)
    currentFrame.updateReadiness(instance.instanceId, false)
  }

  def unsubscripeReadinessCheck(result: ReadinessCheckResult): Unit = {
    val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
    subscriptions.get(subscriptionName).foreach(_.cancel())
  }

  def reconcileAlreadyStartedInstances(currentFrame: Frame): Frame = {
    if (runSpec.hasReadinessChecks) {
      val instancesAlreadyStarted = currentFrame.instances.valuesIterator.filter { instance =>
        instance.runSpecVersion == runSpec.version && instance.isActive
      }.toVector
      logger.info(s"Reconciling instances during ${runSpec.id} deployment: found ${instancesAlreadyStarted.size} already started instances.")
      instancesAlreadyStarted.foldLeft(currentFrame) { (acc, instance) =>
        reconcileHealthAndReadinessCheck(instance, acc)
      }
    } else currentFrame
  }
}

object ReadinessBehavior {
  case class ReadinessCheckSubscriptionKey(taskId: Task.Id, readinessCheck: String)
  case class ReadinessCheckStreamDone(readinessCheckSubscriptionKey: ReadinessCheckSubscriptionKey, exception: Option[Throwable])
}

trait BaseReadinessScheduling extends StrictLogging {

  val runSpec: RunSpec

  def initiateReadinessCheck(instance: Instance): Unit
  def scheduleReadinessCheck(frame: Frame): Frame = {
    if (runSpec.hasReadinessChecks) {
      frame.instances.valuesIterator.find { instance =>
        val noReadinessCheckScheduled = !frame.instancesReady.contains(instance.instanceId)
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && noReadinessCheckScheduled
      } match {
        case Some(instance) =>
          logger.info(s"Scheduling readiness check for ${instance.instanceId}.")
          initiateReadinessCheck(instance)

          // Mark new instance as not ready
          frame.updateReadiness(instance.instanceId, false)
        case None => frame
      }
    } else {
      logger.info("No need to schedule readiness check.")
      frame
    }
  }
}
