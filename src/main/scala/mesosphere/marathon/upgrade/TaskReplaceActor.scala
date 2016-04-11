package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.upgrade.TaskReplaceActor._
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.{ SortedSet, mutable }
import scala.concurrent.Promise
import scala.concurrent.duration._

class TaskReplaceActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val driver: SchedulerDriver,
    val launchQueue: LaunchQueue,
    val taskTracker: TaskTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ReadinessBehavior with ActorLogging {
  import context.dispatcher

  val tasksToKill = taskTracker.appTasksLaunchedSync(app.id)
  var newTasksStarted: Int = 0
  var oldTaskIds = tasksToKill.map(_.taskId).to[SortedSet]
  val toKill = oldTaskIds.to[mutable.Queue]
  var maxCapacity = (app.instances * (1 + app.upgradeStrategy.maximumOverCapacity)).toInt
  var outstandingKills = Set.empty[Task.Id]
  val periodicalRetryKills: Cancellable = context.system.scheduler.schedule(15.seconds, 15.seconds, self, RetryKills)

  override def preStart(): Unit = {
    super.preStart()
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[HealthStatusChanged])

    val ignitionStrategy = computeRestartStrategy(app, tasksToKill.size)
    maxCapacity = ignitionStrategy.maxCapacity

    for (_ <- 0 until ignitionStrategy.nrToKillImmediately) {
      killNextOldTask()
    }

    reconcileNewTasks()

    log.info("Resetting the backoff delay before restarting the app")
    launchQueue.resetDelay(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    periodicalRetryKills.cancel()
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
    super.postStop()
  }

  override def receive: Receive = readinessBehavior orElse replaceBehavior

  def replaceBehavior: Receive = {
    // New task failed to start, restart it
    case MesosStatusUpdateEvent(slaveId, taskId, FailedToStart(_), _, `appId`, _, _, _, `versionString`, _, _) if !oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      log.error(s"New task $taskId failed on slave $slaveId during app $appId restart")
      taskTerminated(taskId)
      launchQueue.add(app)

    // Old task successfully killed
    case MesosStatusUpdateEvent(slaveId, taskId, KillComplete(_), _, `appId`, _, _, _, _, _, _) if oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      oldTaskIds -= taskId
      outstandingKills -= taskId
      reconcileNewTasks()
      checkFinished()

    case RetryKills =>
      retryKills()

    case x: Any => log.debug(s"Received $x")
  }

  override def taskStatusChanged(taskId: Id): Unit = {
    killNextOldTask(Some(taskId))
    checkFinished()
  }

  def reconcileNewTasks(): Unit = {
    val leftCapacity = math.max(0, maxCapacity - oldTaskIds.size - newTasksStarted)
    val tasksNotStartedYet = math.max(0, app.instances - newTasksStarted)
    val tasksToStartNow = math.min(tasksNotStartedYet, leftCapacity)
    if (tasksToStartNow > 0) {
      log.info(s"Reconciling tasks during app $appId restart: queuing $tasksToStartNow new tasks")
      launchQueue.add(app, tasksToStartNow)
      newTasksStarted += tasksToStartNow
    }
  }

  def killNextOldTask(maybeNewTaskId: Option[Task.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val nextOldTask = toKill.dequeue()

      maybeNewTaskId match {
        case Some(newTaskId: Task.Id) =>
          log.info(s"Killing old $nextOldTask because $newTaskId became reachable")
        case _ =>
          log.info(s"Killing old $nextOldTask")
      }

      outstandingKills += nextOldTask
      driver.killTask(nextOldTask.mesosTaskId)
    }
  }

  def checkFinished(): Unit = {
    if (taskTargetCountReached(app.instances) && oldTaskIds.isEmpty) {
      log.info(s"App All new tasks for $appId are ready and all old tasks have been killed")
      promise.success(())
      context.stop(self)
    }
    else if (log.isDebugEnabled) {
      log.debug(s"For app: [${app.id}] there are [${healthyTasks.size}] healthy and " +
        s"[${readyTasks.size}] ready new instances and " +
        s"[${oldTaskIds.size}] old instances.")
    }
  }

  def retryKills(): Unit = {
    outstandingKills.foreach { id =>
      log.warning(s"Retrying kill of old $id")
      driver.killTask(id.mesosTaskId)
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

  case object RetryKills

  //scalastyle:off
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    driver: SchedulerDriver,
    launchQueue: LaunchQueue,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: AppDefinition,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManager, status, driver, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[upgrade] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  private[upgrade] def computeRestartStrategy(app: AppDefinition, runningTasksCount: Int): RestartStrategy = {
    // in addition to an app definition which passed validation, we require:
    require(app.instances > 0, s"instances must be > 0 but is ${app.instances}")
    require(runningTasksCount >= 0, s"running task count must be >=0 but is $runningTasksCount")

    val minHealthy = (app.instances * app.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (app.instances * (1 + app.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, runningTasksCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= runningTasksCount) {
      if (app.isResident) {
        // Kill enough tasks so that we end up with end up with one task below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = runningTasksCount - minHealthy + 1
        log.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      }
      else {
        log.info(s"maxCapacity == minHealthy: Allow temporary over-capacity of one task to allow restarting")
        maxCapacity += 1
      }
    }

    log.info(s"For minimumHealthCapacity ${app.upgradeStrategy.minimumHealthCapacity} of ${app.id.toString} leave " +
      s"$minHealthy tasks running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningTasksCount running tasks immediately")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewTasks: Boolean = minHealthy < maxCapacity || runningTasksCount - nrToKillImmediately < maxCapacity
    assume(canStartNewTasks, s"must be able to start new tasks")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }

}

