package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, ActorRef }
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, PathId, RunnableSpec, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.ReadinessCheckUpdate
import mesosphere.marathon.upgrade.ReadinessBehavior.{ ReadinessCheckSubscriptionKey, ScheduleReadinessCheckFor }
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

  import context.dispatcher

  //dependencies
  def runSpec: RunnableSpec
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

  /**
    * Hook method which is called, whenever an instance becomes healthy or ready.
    */
  def taskStatusChanged(instanceId: Instance.Id): Unit

  /**
    * Indicates, if a given target count has been reached.
    */
  def taskTargetCountReached(count: Int): Boolean = healthy.size == count && ready.size == count

  /**
    * Actors extending this trait should call this method when they detect that a task is terminated
    * The task will be removed from subsequent sets and all subscriptions will get canceled.
    *
    * @param instanceId the id of the task that has been terminated.
    */
  def instanceTerminated(instanceId: Instance.Id): Unit = {
    healthy -= instanceId
    ready -= instanceId
    subscriptions.keys.filter(_.taskId == instanceId).foreach { key =>
      subscriptions(key).unsubscribe()
      subscriptions -= key
    }
  }

  def healthyTasks: Set[Instance.Id] = healthy
  def readyTasks: Set[Instance.Id] = ready
  def subscriptionKeys: Set[ReadinessCheckSubscriptionKey] = subscriptions.keySet

  override def postStop(): Unit = {
    subscriptions.values.foreach(_.unsubscribe())
  }

  /**
    * Depending on the app definition, this method handles:
    * - app without health checks and without readiness checks
    * - app with health checks and without readiness checks
    * - app without health checks and with readiness checks
    * - app with health checks and with readiness checks
    *
    * The #taskIsReady function is called, when the task is ready according to the app definition.
    */
  //scalastyle:off cyclomatic.complexity
  val readinessBehavior: Receive = {

    def taskRunBehavior: Receive = {
      def markAsHealthyAndReady(instanceId: Instance.Id): Unit = {
        log.debug(s"Started instance is ready: $instanceId")
        healthy += instanceId
        ready += instanceId
        taskStatusChanged(instanceId)
      }
      def markAsHealthyAndInitiateReadinessCheck(taskId: Instance.Id): Unit = {
        healthy += taskId
        initiateReadinessCheck(taskId)
      }
      def taskIsRunning(taskFn: Instance.Id => Unit): Receive = {
        case MesosStatusUpdateEvent(slaveId, taskId, "TASK_RUNNING", _, `pathId`, _, _, _, `versionString`, _, _) =>
          taskFn(taskId)
      }
      taskIsRunning(if (!hasReadinessChecks(runSpec)) markAsHealthyAndReady else markAsHealthyAndInitiateReadinessCheck)
    }

    def taskHealthBehavior: Receive = {
      def initiateReadinessOnRun: Receive = {
        case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", _, `pathId`, _, _, _, `versionString`, _, _) =>
          initiateReadinessCheck(taskId)
      }
      def handleTaskHealthy: Receive = {
        case HealthStatusChanged(`pathId`, taskId, `version`, true, _, _) if !healthy(taskId) =>
          log.info(s"Task $taskId now healthy for app ${runSpec.id.toString}")
          healthy += taskId
          if (!hasReadinessChecks(runSpec)) ready += taskId
          taskStatusChanged(taskId)
      }
      val handleTaskRunning = if (hasReadinessChecks(runSpec)) initiateReadinessOnRun else Actor.emptyBehavior
      handleTaskRunning orElse handleTaskHealthy
    }

    def initiateReadinessCheck(taskId: Instance.Id): Unit = {
      log.debug(s"Initiate readiness check for task: $taskId")
      val me = self
      instanceTracker.instance(taskId).map {
        case Some(task: Task) =>
          for {
            launched <- task.launched
          } me ! ScheduleReadinessCheckFor(task, launched)
        case _ => // TODO POD support
      }
    }

    def readinessCheckBehavior: Receive = {
      case ScheduleReadinessCheckFor(task, launched) =>
        log.debug(s"Schedule readiness check for task: ${task.id}")
        ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task, launched).foreach { spec =>
          val subscriptionName = ReadinessCheckSubscriptionKey(task.id, spec.checkName)
          val subscription = readinessCheckExecutor.execute(spec).subscribe(self ! _)
          subscriptions += subscriptionName -> subscription
        }

      case result: ReadinessCheckResult =>
        log.info(s"Received readiness check update for task ${result.taskId} with ready: ${result.ready}")
        deploymentManager ! ReadinessCheckUpdate(status.plan.id, result)
        //TODO(MV): this code assumes only one readiness check per app (validation rules enforce this)
        if (result.ready) {
          log.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
          ready += result.taskId
          val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
          subscriptions.get(subscriptionName).foreach(_.unsubscribe())
          subscriptions -= subscriptionName
          taskStatusChanged(result.taskId)
        }
    }

    val startBehavior = if (hasHealthChecks(runSpec)) taskHealthBehavior else taskRunBehavior
    val readinessBehavior = if (hasReadinessChecks(runSpec)) readinessCheckBehavior else Actor.emptyBehavior
    startBehavior orElse readinessBehavior
  }

  def hasHealthChecks(runSpec: RunnableSpec): Boolean = {
    runSpec match {
      case app: AppDefinition if app.healthChecks.nonEmpty => true
      case _ => false
    }
  }

  def hasReadinessChecks(runSpec: RunnableSpec): Boolean = {
    runSpec match {
      case app: AppDefinition if app.readinessChecks.nonEmpty => true
      case _ => false
    }
  }
}

object ReadinessBehavior {
  case class ReadinessCheckSubscriptionKey(taskId: Instance.Id, readinessCheck: String)
  case class ScheduleReadinessCheckFor(task: Task, launched: Task.Launched)
}
