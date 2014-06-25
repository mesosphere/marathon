package mesosphere.marathon.upgrade

import mesosphere.marathon.event.{ MarathonHealthCheckEvent, MesosStatusUpdateEvent, HealthStatusChanged }
import akka.actor.{ ActorLogging, Actor }
import mesosphere.marathon.api.v1.AppDefinition
import akka.event.EventStream

trait StartingBehavior { this: Actor with ActorLogging =>

  def eventBus: EventStream
  val app: AppDefinition
  def expectedSize: Int
  def withHealthChecks: Boolean
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
  }

  final def receive =
    if (withHealthChecks) checkForHealthy
    else checkForRunning

  final def checkForHealthy: Receive = {
    case HealthStatusChanged(app.`id`, taskId, true, _, _) if !healthyTasks(taskId) =>
      healthyTasks += taskId
      if (healthyTasks.size == expectedSize) {
        success()
      }

    case x => log.debug(s"Received $x")
  }

  final def checkForRunning: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", app.`id`, _, _, Version, _, _) =>
      runningTasks += 1
      if (runningTasks == expectedSize) {
        success()
      }

    case x => log.debug(s"Received $x")
  }

  def success(): Unit
}
