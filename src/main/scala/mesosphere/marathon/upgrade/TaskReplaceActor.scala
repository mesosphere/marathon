package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.upgrade.TaskReplaceActor._
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.{ SortedSet, mutable }
import scala.concurrent.Promise

class TaskReplaceActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val driver: SchedulerDriver,
    val killService: TaskKillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with ReadinessBehavior with ActorLogging {

  val tasksToKill = instanceTracker.specInstancesLaunchedSync(runSpec.id)
  var newTasksStarted: Int = 0
  var oldTaskIds = tasksToKill.map(_.id).to[SortedSet]
  val toKill = tasksToKill.to[mutable.Queue]
  var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
  var outstandingKills = Set.empty[Instance.Id]

  override def preStart(): Unit = {
    super.preStart()
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[HealthStatusChanged])

    val ignitionStrategy = computeRestartStrategy(runSpec, tasksToKill.size)
    maxCapacity = ignitionStrategy.maxCapacity

    for (_ <- 0 until ignitionStrategy.nrToKillImmediately) {
      killNextOldTask()
    }

    reconcileNewTasks()

    log.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
    super.postStop()
  }

  override def receive: Receive = readinessBehavior orElse replaceBehavior

  def replaceBehavior: Receive = {
    // New task failed to start, restart it
    case MesosStatusUpdateEvent(slaveId, taskId, FailedToStart(_), _, `pathId`, _, _, _, `versionString`, _, _) if !oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      log.error(s"New task $taskId failed on slave $slaveId during app $pathId restart")
      instanceTerminated(taskId)
      launchQueue.add(runSpec)

    // Old task successfully killed
    case MesosStatusUpdateEvent(slaveId, taskId, KillComplete(_), _, `pathId`, _, _, _, _, _, _) if oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      oldTaskIds -= taskId
      outstandingKills -= taskId
      reconcileNewTasks()
      checkFinished()

    case x: Any => log.debug(s"Received $x")
  }

  override def taskStatusChanged(taskId: Instance.Id): Unit = {
    if (healthyTasks(taskId) && readyTasks(taskId)) killNextOldTask(Some(taskId))
    checkFinished()
  }

  def reconcileNewTasks(): Unit = {
    val leftCapacity = math.max(0, maxCapacity - oldTaskIds.size - newTasksStarted)
    val tasksNotStartedYet = math.max(0, runSpec.instances - newTasksStarted)
    val tasksToStartNow = math.min(tasksNotStartedYet, leftCapacity)
    if (tasksToStartNow > 0) {
      log.info(s"Reconciling tasks during app $pathId restart: queuing $tasksToStartNow new tasks")
      launchQueue.add(runSpec, tasksToStartNow)
      newTasksStarted += tasksToStartNow
    }
  }

  def killNextOldTask(maybeNewTaskId: Option[Instance.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val nextOldTask = toKill.dequeue()

      maybeNewTaskId match {
        case Some(newTaskId: Instance.Id) =>
          log.info(s"Killing old $nextOldTask because $newTaskId became reachable")
        case _ =>
          log.info(s"Killing old $nextOldTask")
      }

      outstandingKills += nextOldTask.id
      killService.killTask(nextOldTask, TaskKillReason.Upgrading)
    }
  }

  def checkFinished(): Unit = {
    if (taskTargetCountReached(runSpec.instances) && oldTaskIds.isEmpty) {
      log.info(s"All new tasks for $pathId are ready and all old tasks have been killed")
      promise.success(())
      context.stop(self)
    } else if (log.isDebugEnabled) {
      log.debug(s"For app: [${runSpec.id}] there are [${healthyTasks.size}] healthy and " +
        s"[${readyTasks.size}] ready new instances and " +
        s"[${oldTaskIds.size}] old instances.")
    }
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}

object TaskReplaceActor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  val KillComplete = "^TASK_(ERROR|FAILED|FINISHED|LOST|KILLED)$".r
  val FailedToStart = "^TASK_(ERROR|FAILED|LOST|KILLED)$".r

  //scalastyle:off
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    killService: TaskKillService,
    launchQueue: LaunchQueue,
    taskTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManager, status, driver, killService, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[upgrade] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  // TODO (pods): this function should probably match against the type?
  private[upgrade] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): RestartStrategy = {
    // in addition to a spec which passed validation, we require:
    require(runSpec.instances > 0, s"instances must be > 0 but is ${runSpec.instances}")
    require(runningInstancesCount >= 0, s"running instances count must be >=0 but is $runningInstancesCount")

    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, runningInstancesCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= runningInstancesCount) {
      if (runSpec.residency.isDefined) {
        // Kill enough tasks so that we end up with one task below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = runningInstancesCount - minHealthy + 1
        log.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        log.info(s"maxCapacity == minHealthy: Allow temporary over-capacity of one task to allow restarting")
        maxCapacity += 1
      }
    }

    log.info(s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
      s"$minHealthy tasks running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningInstancesCount running tasks immediately")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewTasks: Boolean = minHealthy < maxCapacity || runningInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewTasks, s"must be able to start new tasks")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }

}

