package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.{ Protos, SchedulerDriver }

import scala.collection.mutable
import scala.concurrent.Promise

class TaskReplaceActor(
    driver: SchedulerDriver,
    taskQueue: TaskQueue,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ActorLogging {

  val tasksToKill = taskTracker.get(app.id).filter(_.getId != app.id.toString)
  val appId = app.id
  val version = app.version.toString
  var healthy = Set.empty[String]
  var taskIds = tasksToKill.map(_.getId)
  val toKill = taskIds.to[mutable.Queue]

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[HealthStatusChanged])

    val minHealthy = (toKill.size.toDouble * app.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val nrToKill = math.min(0, toKill.size - minHealthy)

    for (_ <- 0 until nrToKill) {
      val taskId = Protos.TaskID.newBuilder
        .setValue(toKill.dequeue())
        .build()

      driver.killTask(taskId)
    }

    for (_ <- 0 until app.instances) taskQueue.add(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  override def receive: Receive = {
    val behavior =
      if (app.healthChecks.nonEmpty)
        healthCheckingBehavior
      else
        taskStateBehavior

    behavior orElse commonBehavior
  }

  def taskStateBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, "TASK_RUNNING", _, `appId`, _, _, `version`, _, _) =>
      handleNewHealthyTask(taskId)
  }

  def healthCheckingBehavior: PartialFunction[Any, Unit] = {
    case HealthStatusChanged(`appId`, taskId, `version`, true, _, _) if !healthy(taskId) =>
      handleNewHealthyTask(taskId)
  }

  def commonBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, ErrorState(_), _, `appId`, _, _, `version`, _, _) if !taskIds(taskId) => // scalastyle:ignore line.size.limit
      val msg = s"Task $taskId failed on slave $slaveId"
      log.error(msg)
      healthy -= taskId
      taskQueue.add(app)

    case x => log.debug(s"Received $x")
  }

  def handleNewHealthyTask(taskId: String): Unit = {
    healthy += taskId
    killOldTask(taskId)
    checkFinished()
  }

  def killOldTask(newTaskId: String): Unit = {
    if (toKill.nonEmpty) {
      val killing = toKill.dequeue()
      log.info(s"Killing old task $killing because $newTaskId became reachable")
      driver.killTask(buildTaskId(killing))
    }
  }

  def checkFinished(): Unit = {
    if (healthy.size == app.instances) {
      log.info(s"All tasks for $appId are healthy")
      promise.success(())
      context.stop(self)
    }
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}

private object ErrorState {
  def unapply(state: String): Option[String] = state match {
    case "TASK_ERROR" | "TASK_FAILED" | "TASK_KILLED" | "TASK_LOST" => Some(state)
    case _ => None
  }
}
