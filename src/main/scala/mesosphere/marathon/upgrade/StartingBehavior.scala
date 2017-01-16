package mesosphere.marathon.upgrade

import akka.actor.Actor
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.condition.Condition.Terminal
import mesosphere.marathon.core.event.{ InstanceChanged, InstanceHealthChanged }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.InstanceTracker
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

trait StartingBehavior extends ReadinessBehavior { this: Actor =>
  import context.dispatcher
  import mesosphere.marathon.upgrade.StartingBehavior._

  def eventBus: EventStream
  def scaleTo: Int
  def nrToStart: Int
  def launchQueue: LaunchQueue
  def scheduler: SchedulerActions
  def instanceTracker: InstanceTracker

  def initializeStart(): Unit

  private[this] val log = LoggerFactory.getLogger(getClass)

  final override def preStart(): Unit = {
    if (hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    initializeStart()
    checkFinished()
    context.system.scheduler.scheduleOnce(1.seconds, self, Sync)
  }

  final override def receive: Receive = readinessBehavior orElse commonBehavior

  def commonBehavior: Receive = {
    case InstanceChanged(id, `version`, `pathId`, _: Terminal, _) =>
      log.warn(s"New instance [$id] failed during app ${runSpec.id.toString} scaling, queueing another instance")
      instanceTerminated(id)
      launchQueue.add(runSpec)

    case Sync =>
      val actualSize = launchQueue.get(runSpec.id)
        .fold(instanceTracker.countLaunchedSpecInstancesSync(runSpec.id))(_.finalInstanceCount)
      val instancesToStartNow = Math.max(scaleTo - actualSize, 0)
      log.debug(s"Sync start instancesToStartNow=$instancesToStartNow appId=${runSpec.id}")
      if (instancesToStartNow > 0) {
        log.info(s"Reconciling app ${runSpec.id} scaling: queuing $instancesToStartNow new instances")
        launchQueue.add(runSpec, instancesToStartNow)
      }
      context.system.scheduler.scheduleOnce(5.seconds, self, Sync)

    case DeploymentActor.Shutdown =>
      shutdown()
  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    log.debug(s"New instance $instanceId changed during app ${runSpec.id} scaling, " +
      s"${readyInstances.size} ready ${healthyInstances.size} healthy need $nrToStart")
    checkFinished()
  }

  def checkFinished(): Unit = {
    if (targetCountReached(nrToStart)) success()
  }

  def success(): Unit

  def shutdown(): Unit
}

object StartingBehavior {
  case object Sync
}

