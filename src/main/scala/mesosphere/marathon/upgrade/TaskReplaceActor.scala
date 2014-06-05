package mesosphere.marathon.upgrade

import akka.actor.{ ActorLogging, Actor }
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.Promise
import mesosphere.marathon.event.{ MesosStatusUpdateEvent, HealthStatusChanged }
import org.apache.mesos.SchedulerDriver
import scala.collection.mutable
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.{ TaskUpgradeCancelledException, TaskFailedException }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskQueue

class TaskReplaceActor(
    driver: SchedulerDriver,
    taskQueue: TaskQueue,
    eventBus: EventStream,
    app: AppDefinition,
    var alreadyStarted: Int,
    tasksToKill: Set[MarathonTask],
    promise: Promise[Boolean]) extends Actor with ActorLogging {

  eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
  eventBus.subscribe(self, classOf[HealthStatusChanged])

  val appId = app.id
  val version = app.version.toString
  val nrToStart = app.instances

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCancelledException(
          "The task upgrade has been cancelled"))
  }

  var healthy = Set.empty[String]
  var taskIds = tasksToKill.map(_.getId)
  val toKill = taskIds.to[mutable.Queue]

  def receive: Receive = {
    case HealthStatusChanged(`appId`, taskId, true, _, _) if !taskIds(taskId) =>
      healthy += taskId
      if (toKill.nonEmpty) {
        val killing = toKill.dequeue()
        log.info(s"Killing old task $killing because $taskId became reachable")
        driver.killTask(buildTaskId(killing))

        if (alreadyStarted < nrToStart) {
          log.info(s"Starting new task for app ${app.id}")
          taskQueue.add(app)
        }
      }
      if (healthy.size == nrToStart) {
        log.info(s"All tasks for $appId are healthy")
        promise.success(true)
        context.stop(self)
      }

    case MesosStatusUpdateEvent(slaveId, taskId, ErrorState(_), `appId`, _, _, `version`, _, _) if !taskIds(taskId) =>
      val msg = s"Task $taskId failed on slave $slaveId"
      log.error(msg)
      promise.failure(new TaskFailedException(msg))
      context.stop(self)

    case x => log.debug(s"Received $x")
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}

private object ErrorState {
  def unapply(state: String): Option[String] = state match {
    case "TASK_FAILED" | "TASK_KILLED" | "TASK_LOST" => Some(state)
    case _ => None
  }
}
