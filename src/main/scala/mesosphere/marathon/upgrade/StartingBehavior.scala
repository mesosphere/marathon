package mesosphere.marathon.upgrade

import akka.actor.{ ActorLogging, Actor }
import akka.event.EventStream
import mesosphere.marathon.event.{ MarathonHealthCheckEvent, MesosStatusUpdateEvent, HealthStatusChanged }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskQueue

trait StartingBehavior { this: Actor with ActorLogging =>

  def eventBus: EventStream
  val app: AppDefinition
  def expectedSize: Int
  def withHealthChecks: Boolean
  def taskQueue: TaskQueue
  val Version = app.version.toString
  var healthyTasks = Set.empty[String]
  var runningTasks = 0

  def initializeStart(): Unit

  final override def preStart(): Unit = {
    if (withHealthChecks) {
      eventBus.subscribe(self, classOf[MarathonHealthCheckEvent])
    }
    else {
      eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    }

    initializeStart()
    checkFinished()
  }

  final def receive = {
    val behavior =
      if (withHealthChecks) checkForHealthy
      else checkForRunning
    behavior orElse rescheduleOnFailure
  }

  final def checkForHealthy: Receive = {
    case HealthStatusChanged(app.`id`, taskId, Version, true, _, _) if !healthyTasks(taskId) =>
      healthyTasks += taskId
      log.info(s"$taskId is now healthy")
      checkFinished()

    case x => log.debug(s"Received $x")
  }

  final def checkForRunning: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", app.`id`, _, _, Version, _, _) =>
      runningTasks += 1
      log.info(s"Started $taskId")
      checkFinished()

    case x => log.debug(s"Received $x")
  }

  def rescheduleOnFailure: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_FAILED" | "TASK_LOST" | "TASK_KILLED", app.`id`, _, _, Version, _, _) =>
      log.warning(s"Failed to start $taskId for app ${app.id}. Rescheduling.")
      taskQueue.add(app)
  }

  def checkFinished(): Unit = {
    if (withHealthChecks && healthyTasks.size == expectedSize) {
      success()
    }
    else if (runningTasks == expectedSize) {
      success()
    }
  }

  def success(): Unit
}
