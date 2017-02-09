package mesosphere.marathon.cron

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.schedule.Periodic

import scala.concurrent.duration._


class CronMonitorActor private(
                                createSchedulerActions: ActorRef => SchedulerActions,
                                launchQueue: LaunchQueue,
                                period: FiniteDuration
                              ) extends Actor with ActorLogging {
  import CronMonitorActor._
  import Messages._
  import Messages.Private._

  // Actor State
  private var monitorTick: Option[Cancellable] = None
  private var schedulerActions: SchedulerActions = _
  // End of actor state

  //TODO: Leverage instance tracker to avoid launching already running tasks
  //TODO: Manage schedule expression so no all tasks will be launched after the period

  override def preStart(): Unit = {
    monitorTick = Some(context.system.scheduler.schedule(period, period, self, PerformCheck))
    schedulerActions = createSchedulerActions(self)
  }

  private def receiveCommands: Receive = {
    case PerformCheck =>
      launchCronApps
  }

  private def receiveInternalEvents: Receive = {
    case LaunchPeriodicTasks(taks) =>
      taks foreach { runSpec =>
        launchQueue.add(runSpec)
      }
    /* NOTE: Not handling failure in the RunSpec
              retrieval phase, that message will be lost.
              Marathon will should down in any.
       */
  }

  override def receive: Receive = receiveCommands orElse receiveInternalEvents

  override def postStop(): Unit = {
    monitorTick foreach (_.cancel())
  }

  //

  def launchCronApps: Unit = {
    val periodicRunSepcsFut = schedulerActions.groupRepository.root() map { root =>
      val periodicRunSpecs = root.transitiveApps filter { appDef =>
        appDef.schedule.strategy match {
          case _: Periodic => true
          case _ => false
        }
      }
      LaunchPeriodicTasks(periodicRunSpecs)
    }
    import akka.pattern.pipe
    import context.dispatcher
    periodicRunSepcsFut pipeTo self
  }

}


object CronMonitorActor {

  def props(
             createSchedulerActions: ActorRef => SchedulerActions,
             launchQueue: LaunchQueue,
             period: FiniteDuration = 30 minutes
           ): Props = Props {
      new CronMonitorActor(createSchedulerActions, launchQueue, period)
    }

  object Messages {
    object PerformCheck

    private[CronMonitorActor] object Private {
      case class LaunchPeriodicTasks(runSpecs: Set[AppDefinition])
    }
  }

}