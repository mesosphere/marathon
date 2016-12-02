package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorRef }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, RunSpec, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.ReadinessCheckUpdate
import org.slf4j.LoggerFactory
import rx.lang.scala.Subscription

/**
  * ReadinessBehavior makes sure all tasks are healthy and ready depending on an app definition.
  * Listens for TaskStatusUpdate events, HealthCheck events and ReadinessCheck events.
  * If a task becomes ready, the taskTargetCountReached hook is called.
  *
  * Assumptions:
  *  - the actor is attached to the event stream for HealthStatusChanged and MesosStatusUpdateEvent
  */
trait ReadinessBehavior { this: Actor =>

  import ReadinessBehavior._

  //dependencies
  def runSpec: RunSpec
  def readinessCheckExecutor: ReadinessCheckExecutor
  def deploymentManager: ActorRef
  def instanceTracker: InstanceTracker
  def status: DeploymentStatus

  //computed values to have stable identifier in pattern matcher
  val pathId: PathId = runSpec.id
  val version: Timestamp = runSpec.version
  val versionString: String = version.toString

  //state managed by this behavior
  private[this] var healthy = Set.empty[Instance.Id]
  private[this] var ready = Set.empty[Instance.Id]
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Subscription]
  private[this] val log = LoggerFactory.getLogger(getClass)

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
      subscriptions(key).unsubscribe()
      subscriptions -= key
    }
  }

  def healthyInstances: Set[Instance.Id] = healthy
  def readyInstances: Set[Instance.Id] = ready
  def subscriptionKeys: Set[ReadinessCheckSubscriptionKey] = subscriptions.keySet

  override def postStop(): Unit = {
    subscriptions.values.foreach(_.unsubscribe())
  }

  protected def initiateReadinessCheck(instance: Instance): Unit = {
    def initiateReadinessCheckForTask(task: Task): Unit = {
      log.debug(s"Schedule readiness check for task: ${task.taskId}")
      ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task).foreach { spec =>
        val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
        val subscription = readinessCheckExecutor.execute(spec).subscribe(self ! _)
        subscriptions += subscriptionName -> subscription
      }
    }
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
        log.debug(s"Started instance is ready: ${instance.instanceId}")
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
          log.info(s"Instance $id now healthy for run spec ${runSpec.id}")
          healthy += id
          if (!hasReadinessChecks) ready += id
          instanceConditionChanged(id)
      }
      val handleInstanceRunning = if (hasReadinessChecks) initiateReadinessOnRun else Actor.emptyBehavior
      handleInstanceRunning orElse handleInstanceHealthy
    }

    def initiateReadinessCheck(instance: Instance): Unit = {
      def initiateReadinessCheckForTask(task: Task): Unit = {
        log.debug(s"Schedule readiness check for task: ${task.taskId}")
        ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task).foreach { spec =>
          val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
          val subscription = readinessCheckExecutor.execute(spec).subscribe(self ! _)
          subscriptions += subscriptionName -> subscription
        }
      }
      instance.tasksMap.foreach {
        case (_, task) =>
          if (task.isRunning) initiateReadinessCheckForTask(task)
      }
    }

    def readinessCheckBehavior: Receive = {
      case result: ReadinessCheckResult =>
        log.info(s"Received readiness check update for task ${result.taskId} with ready: ${result.ready}")
        deploymentManager ! ReadinessCheckUpdate(status.plan.id, result)
        //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
        if (result.ready) {
          log.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
          ready += result.taskId.instanceId
          val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
          subscriptions.get(subscriptionName).foreach(_.unsubscribe())
          subscriptions -= subscriptionName
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
        log.debug(s"Instance is already known as healthy: ${instance.instanceId}")
        healthy += instance.instanceId
        if (hasReadinessChecks) initiateReadinessCheck(instance)
      } else {
        log.info(s"Wait for health check to pass for instance: ${instance.instanceId}")
      }
    }

    if (hasHealthChecks) withHealth() else healthy += instance.instanceId
    if (hasReadinessChecks) initiateReadinessCheck(instance) else ready += instance.instanceId
  }
}

object ReadinessBehavior {
  case class ReadinessCheckSubscriptionKey(taskId: Task.Id, readinessCheck: String)
}
