package mesosphere.marathon.upgrade

import akka.actor.{Actor, ActorLogging}
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.event.{MarathonHealthCheckEvent, MesosStatusUpdateEvent}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.InstanceTracker
import org.apache.mesos.SchedulerDriver

import scala.concurrent.duration._

trait StartingBehavior extends ReadinessBehavior { this: Actor with ActorLogging =>
  import context.dispatcher
  import mesosphere.marathon.upgrade.StartingBehavior._

  def eventBus: EventStream
  def scaleTo: Int
  def nrToStart: Int
  def launchQueue: LaunchQueue
  def driver: SchedulerDriver
  def scheduler: SchedulerActions
  def instanceTracker: InstanceTracker

  def initializeStart(): Unit

  final override def preStart(): Unit = {
    if (runSpec.healthChecks.nonEmpty) eventBus.subscribe(self, classOf[MarathonHealthCheckEvent])
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])

    initializeStart()
    checkFinished()

    context.system.scheduler.scheduleOnce(5.seconds, self, Sync)
  }

  final override def receive: Receive = readinessBehavior orElse commonBehavior

  def commonBehavior: Receive = {
    case MesosStatusUpdateEvent(_, taskId, StartErrorState(_), _, `pathId`, _, _, _, `versionString`, _, _) => // scalastyle:off line.size.limit
      log.warning(s"New task [$taskId] failed during app ${runSpec.id.toString} scaling, queueing another task")
      instanceTerminated(taskId)
      launchQueue.add(runSpec)

    case Sync =>
      val actualSize = launchQueue.get(runSpec.id).map(_.finalTaskCount).getOrElse(instanceTracker.countLaunchedSpecInstancesSync(runSpec.id))
      val tasksToStartNow = Math.max(scaleTo - actualSize, 0)
      if (tasksToStartNow > 0) {
        log.info(s"Reconciling tasks during app ${runSpec.id.toString} scaling: queuing $tasksToStartNow new tasks")
        launchQueue.add(runSpec, tasksToStartNow)
      }
      context.system.scheduler.scheduleOnce(5.seconds, self, Sync)
  }

  override def taskStatusChanged(taskId: Instance.Id): Unit = {
    log.info(s"New task $taskId changed during app ${runSpec.id.toString} scaling, " +
      s"${readyTasks.size} ready ${healthyTasks.size} healthy need $nrToStart")
    checkFinished()
  }

  def checkFinished(): Unit = {
    if (taskTargetCountReached(nrToStart)) success()
  }

  def success(): Unit
}

object StartingBehavior {
  case object Sync
}

private object StartErrorState {
  def unapply(state: String): Option[String] = state match {
    case "TASK_ERROR" | "TASK_FAILED" | "TASK_KILLED" | "TASK_LOST" => Some(state)
    case _ => None
  }
}
