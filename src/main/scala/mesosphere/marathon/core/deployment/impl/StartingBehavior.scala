package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.pattern._
import akka.actor.{Actor, Status}
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.impl.StartingBehavior.{PostStart, Sync}
import mesosphere.marathon.core.event.{InstanceChanged, InstanceHealthChanged}
import mesosphere.marathon.core.instance.{Goal, Instance}

import scala.async.Async.{async, await}
import scala.concurrent.Future
import scala.concurrent.duration._

trait StartingBehavior extends ReadinessBehavior with StrictLogging { this: Actor =>
  import context.dispatcher

  def eventBus: EventStream
  def scaleTo: Int
  def nrToStart: Future[Int]
  def scheduler: scheduling.Scheduler

  def initializeStart(): Future[Done]

  final override def preStart(): Unit = {
    if (hasHealthChecks) eventBus.subscribe(self, classOf[InstanceHealthChanged])
    eventBus.subscribe(self, classOf[InstanceChanged])

    async {
      await(initializeStart())
      // We send ourselves a message to check if we're already finished since checking it in this async block
      // would lead to a race condition because it's touching actor's state.
      PostStart
    }.pipeTo(self)
  }

  final override def receive: Receive = readinessBehavior orElse commonBehavior

  def commonBehavior: Receive = {
    case InstanceChanged(id, `version`, `pathId`, condition: Condition, instance) if condition.isTerminal || instance.isReservedTerminal =>
      logger.warn(s"New instance [$id] failed during app ${runSpec.id.toString} scaling, queueing another instance")
      instanceTerminated(id)
      scheduler.schedule(runSpec, 1).pipeTo(self)

    case Sync => async {
      val instances = await(scheduler.getInstances(runSpec.id))
      val actualSize = instances.count { i => i.isActive || i.isScheduled }
      val instancesToStartNow = Math.max(scaleTo - actualSize, 0)
      logger.debug(s"Sync start instancesToStartNow=$instancesToStartNow appId=${runSpec.id}")
      if (instancesToStartNow > 0) {
        logger.info(s"Reconciling app ${runSpec.id} scaling: queuing $instancesToStartNow new instances")
        // Reschedule stopped resident instances first.
        val existingReservedStoppedInstances = instances
          .filter(i => i.isReserved && i.state.goal == Goal.Stopped) // resident to relaunch
          .take(instancesToStartNow)
        await(scheduler.reschedule(existingReservedStoppedInstances, runSpec))

        // Schedule remaining instances
        val instancesToSchedule = math.max(0, instancesToStartNow - existingReservedStoppedInstances.length)
        await(scheduler.schedule(runSpec, instancesToSchedule))
      }
      context.system.scheduler.scheduleOnce(StartingBehavior.syncInterval, self, Sync)
      Done // Otherwise we will pipe the result of scheduleOnce(...) call which is a Cancellable
    }.pipeTo(self)

    case Status.Failure(e) =>
      // This is the result of failed initializeStart(...) call. Log the message and
      // restart this actor. Next reincarnation should try to start from the beginning.
      logger.warn(s"Failure in the ${getClass.getSimpleName} deployment actor: ", e)
      throw e

    case PostStart =>
      checkFinished()
      context.system.scheduler.scheduleOnce(StartingBehavior.firstSyncDelay, self, Sync)

    case Done => // This is the result of successful initializeStart(...) call. Nothing to do here
  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    logger.info(s"New instance $instanceId changed during app ${runSpec.id} scaling, " +
      s"${readyInstances.size} ready ${healthyInstances.size} healthy need ${nrToStart.value}")
    checkFinished()
  }

  def checkFinished(): Unit = {
    nrToStart.foreach{ n =>
      if (targetCountReached(n)) success()
    }
  }

  def success(): Unit
}

object StartingBehavior {
  case object Sync
  case object PostStart

  val firstSyncDelay = 1.seconds
  val syncInterval = 5.seconds
}

