package mesosphere.marathon
package core.deployment.impl

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.ReadinessCheckUpdate
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec, Timestamp}

/**
  * ReadinessBehavior makes sure all tasks are healthy and ready depending on an app definition.
  * Listens for TaskStatusUpdate events, HealthCheck events and ReadinessCheck events.
  * If a task becomes ready, the taskTargetCountReached hook is called.
  *
  * Assumptions:
  *  - the actor is attached to the event stream for HealthStatusChanged and MesosStatusUpdateEvent
  */
trait ReadinessBehavior extends StrictLogging { this: Actor =>

  implicit private val materializer = ActorMaterializer()
  import ReadinessBehavior._

  //dependencies
  def runSpec: RunSpec
  def readinessCheckExecutor: ReadinessCheckExecutor
  def deploymentManagerActor: ActorRef
  def status: DeploymentStatus

  //computed values to have stable identifier in pattern matcher
  val pathId: PathId = runSpec.id
  val version: Timestamp = runSpec.version
  val versionString: String = version.toString

  //state managed by this behavior
  private[this] var healthy = Set.empty[Instance.Id]
  private[this] var ready = Set.empty[Instance.Id]
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Cancellable]

  protected val hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }

  protected val hasReadinessChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.readinessChecks.nonEmpty
      case pod: PodDefinition => false // TODO(PODS) support readiness post-MVP
    }
  }

  /**
    * Hook method which is called, whenever an instance becomes healthy or ready.
    */
  def instanceConditionChanged(instanceId: Instance.Id): Unit

  /**
    * Indicates, if a given target count has been reached.
    */
  def targetCountReached(count: Int): Boolean = healthy.size == count && ready.size == count

  /**
    * Actors extending this trait should call this method when they detect that a instance is terminated
    * The instance will be removed from subsequent sets and all subscriptions will get canceled.
    *
    * @param instanceId the id of the task that has been terminated.
    */
  def instanceTerminated(instanceId: Instance.Id): Unit = {
    healthy -= instanceId
    ready -= instanceId
    subscriptions.keys.withFilter(_.taskId.instanceId == instanceId).foreach { key =>
      subscriptions.get(key).foreach(_.cancel())
    }
  }

  def healthyInstances: Set[Instance.Id] = healthy
  def readyInstances: Set[Instance.Id] = ready
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

  protected def initiateReadinessCheck(instance: Instance): Unit = {
    instance.tasksMap.foreach {
      case (_, task) => initiateReadinessCheckForTask(task)
    }
  }

  /**
    * Depending on the run spec definition, this method handles:
    * - run spec without health checks and without readiness checks
    * - run spec with health checks and without readiness checks
    * - run spec without health checks and with readiness checks
    * - run spec with health checks and with readiness checks
    *
    * The #taskIsReady function is called, when the task is ready according to the app definition.
    */
  val readinessBehavior: Receive = {

    def instanceRunBehavior: Receive = {
      def markAsHealthyAndReady(instance: Instance): Unit = {
        logger.info(s"Started instance is ready: ${instance.instanceId}")
        healthy += instance.instanceId
        ready += instance.instanceId
        instanceConditionChanged(instance.instanceId)
      }
      def markAsHealthyAndInitiateReadinessCheck(instance: Instance): Unit = {
        healthy += instance.instanceId
        initiateReadinessCheck(instance)
      }
      def instanceIsRunning(instanceFn: Instance => Unit): Receive = {
        case InstanceChanged(_, `version`, `pathId`, Running, instance) => instanceFn(instance)
      }
      instanceIsRunning(
        if (!hasReadinessChecks) markAsHealthyAndReady else markAsHealthyAndInitiateReadinessCheck)
    }

    def instanceHealthBehavior: Receive = {
      def initiateReadinessOnRun: Receive = {
        case InstanceChanged(_, `version`, `pathId`, Running, instance) => initiateReadinessCheck(instance)
      }
      def handleInstanceHealthy: Receive = {
        case InstanceHealthChanged(id, `version`, `pathId`, Some(true)) if !healthy(id) =>
          logger.info(s"Instance $id now healthy for run spec ${runSpec.id}")
          healthy += id
          if (!hasReadinessChecks) ready += id
          instanceConditionChanged(id)
      }
      val handleInstanceRunning = if (hasReadinessChecks) initiateReadinessOnRun else Actor.emptyBehavior
      handleInstanceRunning orElse handleInstanceHealthy
    }

    def initiateReadinessCheck(instance: Instance): Unit = {
      instance.tasksMap.foreach {
        case (_, task) =>
          if (task.isRunning) initiateReadinessCheckForTask(task)
      }
    }

    def readinessCheckBehavior: Receive = {
      case ReadinessCheckStreamDone(subscriptionName, maybeFailure) =>
        maybeFailure.foreach { ex =>
          // We should not ever get here
          logger.error(s"Received an unexpected failure for readiness stream ${subscriptionName}", ex)
        }
        logger.debug(s"Readiness check stream ${subscriptionName} is done")
        subscriptions -= subscriptionName

      case result: ReadinessCheckResult =>
        logger.info(s"Received readiness check update for task ${result.taskId} with ready: ${result.ready}")
        deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
        //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
        if (result.ready) {
          logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
          ready += result.taskId.instanceId
          val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
          subscriptions.get(subscriptionName).foreach(_.cancel())
          instanceConditionChanged(result.taskId.instanceId)
        }
    }

    val startBehavior = if (hasHealthChecks) instanceHealthBehavior else instanceRunBehavior
    val readinessBehavior = if (hasReadinessChecks) readinessCheckBehavior else Actor.emptyBehavior
    startBehavior orElse readinessBehavior
  }

  /**
    * Call this method for instances that are already started.
    * This should be necessary only after fail over to reconcile the state from a previous run.
    * It will make sure to wait for health checks and readiness checks to become green.
    * @param instance the instance that has been started.
    */
  def reconcileHealthAndReadinessCheck(instance: Instance): Unit = {
    def withHealth(): Unit = {
      if (instance.state.healthy.getOrElse(false)) {
        logger.debug(s"Instance is already known as healthy: ${instance.instanceId}")
        healthy += instance.instanceId
      } else {
        logger.info(s"Wait for health check to pass for instance: ${instance.instanceId}")
      }
    }

    if (hasHealthChecks) withHealth() else healthy += instance.instanceId
    if (hasReadinessChecks) initiateReadinessCheck(instance) else ready += instance.instanceId
  }
}

object ReadinessBehavior {
  case class ReadinessCheckSubscriptionKey(taskId: Task.Id, readinessCheck: String)
  case class ReadinessCheckStreamDone(readinessCheckSubscriptionKey: ReadinessCheckSubscriptionKey, exception: Option[Throwable])
}
