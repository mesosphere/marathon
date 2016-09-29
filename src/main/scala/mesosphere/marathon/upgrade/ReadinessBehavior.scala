package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, ActorRef }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.InstanceStatus.Running
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, RunSpec, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.ReadinessCheckUpdate
import rx.lang.scala.Subscription

/**
  * ReadinessBehavior makes sure all tasks are healthy and ready depending on an app definition.
  * Listens for TaskStatusUpdate events, HealthCheck events and ReadinessCheck events.
  * If a task becomes ready, the taskTargetCountReached hook is called.
  *
  * Assumptions:
  *  - the actor is attached to the event stream for HealthStatusChanged and MesosStatusUpdateEvent
  */
trait ReadinessBehavior { this: Actor with ActorLogging =>

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

  // TODO(PODS): PodDefinition returns an empty Seq for def healthChecks - however, there might be HCs in containers
  protected final def hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }
  /**
    * Hook method which is called, whenever an instance becomes healthy or ready.
    */
  def instanceStatusChanged(instanceId: Instance.Id): Unit

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
        instanceStatusChanged(instance.instanceId)
      }
      def markAsHealthyAndInitiateReadinessCheck(instance: Instance): Unit = {
        healthy += instance.instanceId
        initiateReadinessCheck(instance)
      }
      def instanceIsRunning(instanceFn: Instance => Unit): Receive = {
        case InstanceChanged(_, `version`, `pathId`, Running, instance) => instanceFn(instance)
      }
      instanceIsRunning(
        if (runSpec.readinessChecks.isEmpty) markAsHealthyAndReady else markAsHealthyAndInitiateReadinessCheck)
    }

    def instanceHealthBehavior: Receive = {
      def initiateReadinessOnRun: Receive = {
        case InstanceChanged(_, `version`, `pathId`, Running, instance) => initiateReadinessCheck(instance)
      }
      def handleInstanceHealthy: Receive = {
        case InstanceHealthChanged(id, `version`, `pathId`, Some(true)) if !healthy(id) =>
          log.info(s"Instance $id now healthy for run spec ${runSpec.id}")
          healthy += id
          if (runSpec.readinessChecks.isEmpty) ready += id
          instanceStatusChanged(id)
      }
      val handleInstanceRunning = if (runSpec.readinessChecks.nonEmpty) initiateReadinessOnRun else Actor.emptyBehavior
      handleInstanceRunning orElse handleInstanceHealthy
    }

    def initiateReadinessCheck(instance: Instance): Unit = {
      def initiateReadinessCheckForTask(task: Task, launched: Task.Launched): Unit = {
        log.debug(s"Schedule readiness check for task: ${task.taskId}")
        ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task, launched).foreach { spec =>
          val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
          val subscription = readinessCheckExecutor.execute(spec).subscribe(self ! _)
          subscriptions += subscriptionName -> subscription
        }
      }
      instance.tasks.foreach { task =>
        task.launched.foreach(initiateReadinessCheckForTask(task, _))
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
          instanceStatusChanged(result.taskId.instanceId)
        }
    }

    val startBehavior = if (hasHealthChecks) instanceHealthBehavior else instanceRunBehavior
    val readinessBehavior = if (runSpec.readinessChecks.nonEmpty) readinessCheckBehavior else Actor.emptyBehavior
    startBehavior orElse readinessBehavior
  }
}

object ReadinessBehavior {
  case class ReadinessCheckSubscriptionKey(taskId: Task.Id, readinessCheck: String)
}
