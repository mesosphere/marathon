package mesosphere.marathon.upgrade

import akka.actor.{Cancellable, ActorRef, Actor}
import mesosphere.marathon.MarathonScheduler
import mesosphere.marathon.tasks.TaskQueue
import mesosphere.marathon.api.v2.AppUpdate
import scala.concurrent.duration._
import org.apache.log4j.Logger
import com.google.common.eventbus.{Subscribe, EventBus}
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.apache.mesos.SchedulerDriver
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.api.v1.AppDefinition

class StatusUpdateHandler(receiver: ActorRef) {
  @Subscribe
  def handleStatusUpdate(update: MesosStatusUpdateEvent) = receiver ! update
}

class DeployActor(
  scheduler: MarathonScheduler,
  driver: SchedulerDriver,
  taskQueue: TaskQueue,
  eventBus: EventBus,
  deploymentPlan: DeploymentPlan
) extends Actor {
  import DeployActor._
  import context.dispatcher

  private [this] val log = Logger.getLogger(getClass)

  val steps = deploymentPlan.steps.iterator
  var handler: Option[Any] = None
  var schedule: Option[Cancellable] = None

  override def preStart() {
    handler = Some(new StatusUpdateHandler(self))
    handler.foreach(eventBus.register)
    self ! NextStep
  }

  override def postStop() {
    handler.foreach(eventBus.unregister)
  }

  def receive = initial

  def initial: Receive = {
    case NextStep =>
      if(steps.hasNext) {
        val step = steps.next()
        self ! KillTasks(step)
        context.become(kill, true)
      } else {
        context.stop(self)
      }
  }

  def kill: Receive = {
    case KillTasks(step) =>
      val idsToKill = step.deployments.flatMap(_.taskIdsToKill)

      val taskIds = idsToKill.map { taskId =>
        TaskID.newBuilder()
          .setValue(taskId)
          .build()
      }

      taskIds.foreach(driver.killTask)
      context.become(awaitKilled(idsToKill.toSet, step))
  }

  /**
   * waits for all the tasks to be killed
   * @param taskIds
   * @return
   */
  def awaitKilled(taskIds: Set[String], step: DeploymentStep): Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_KILLED", _, _, _, _) if taskIds(taskId) =>
      val rest = taskIds - taskId
      if (rest.nonEmpty)
        context.become(awaitKilled(rest, step), true)
      else
        self ! StartTasks(step)
        context.become(startInstances, true)
  }

  def startInstances: Receive = {
    case StartTasks(step) =>
      for {
        deployment <- step.deployments
        _ <- 0 until deployment.scaleUp
      } taskQueue.add(AppDefinition(id = deployment.appId))

      val instances = step.deployments.map { x =>
        x.appId -> x.scaleUp
      }.toMap
      context.become(awaitRunning(instances, step), true)
  }

  /**
   * waits for all the instances to start, fails if any instance
   * fails to start
   * @param instances Mapping from appId to nr of instances
   * @return
   */
  def awaitRunning(instances: Map[String, Int], step: DeploymentStep): Receive = {
    case MesosStatusUpdateEvent(_, _, "TASK_RUNNING", appId, _, _, _) if instances.contains(appId) =>
      val rest = instances.updated(appId, instances(appId) - 1).filter(_._2 > 0)
      if (rest.nonEmpty) {
        context.become(awaitRunning(rest, step), true)
      } else {
        if (step.waitTime > 0) {
          schedule = Some(context.system.scheduler.scheduleOnce(step.waitTime.seconds, self, WatchTimeout))
          context.become(ensureRunning(step, step.deployments.map(_.appId).toSet), true)
        } else {
          self ! NextStep
          context.become(initial, true)
        }
      }

    case MesosStatusUpdateEvent(_, taskId, "TASK_FAILED", appId, _, _, _) if instances.contains(appId) =>
      log.error(s"$taskId failed to startup, initializing rollback.")
      rollback()
      context.stop(self)
  }

  def ensureRunning(step: DeploymentStep, apps: Set[String]): Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_FAILED", appId, _, _, _) if apps(appId) =>
      schedule.foreach(_.cancel())
      log.error(s"$taskId failed during wait time, initializing rollback.")
      rollback()
      context.stop(self)

    case WatchTimeout =>
      self ! NextStep
      context.become(initial, true)
  }

  def rollback() =
    deploymentPlan.original.foreach { app =>
      scheduler.updateApp(driver, app.id, AppUpdate.fromAppDefinition(app))
    }
}

object DeployActor {
  private case object NextStep
  private case object WatchTimeout
  private case class KillTasks(step: DeploymentStep)
  private case class StartTasks(step: DeploymentStep)
}
