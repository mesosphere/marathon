package mesosphere.marathon.cron

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.state.schedule.Periodic

import scala.concurrent.duration._


class CronMonitorActor private(
                                createSchedulerActions: ActorRef => SchedulerActions,
                                launchQueue: LaunchQueue
                              ) extends Actor with ActorLogging {
  import CronMonitorActor._
  import Messages._

  // Actor State
  private var monitorTick: Option[Cancellable] = None
  private var schedulerActions: SchedulerActions = _
  // End of actor state

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
  }

  override def receive: Receive = receiveCommands orElse receiveInternalEvents

  override def postStop(): Unit = {
    monitorTick foreach (_.cancel())
  }

  //

  def launchCronApps: Unit = {
    val periodicRunSepcsFut = schedulerActions.groupRepository.root() map { root =>
      val periodicRunSpecs = root.transitiveRunSpecs filter { runSpec =>
        runSpec.schedule.strategy match {
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

  private val period: FiniteDuration = 5 minutes

  object Messages {
    object PerformCheck
    case class LaunchPeriodicTasks(runSpecs: Set[RunSpec])
  }

}