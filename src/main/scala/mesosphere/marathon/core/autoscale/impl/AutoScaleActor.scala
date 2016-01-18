package mesosphere.marathon.core.autoscale.impl

import javax.inject.Provider

import akka.actor._
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.autoscale.impl.AutoScaleActor._
import mesosphere.marathon.core.autoscale.{ AutoScaleConfig, AutoScalePolicy }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state._
import mesosphere.util.ThreadPoolContext

object AutoScaleActor {

  def props(conf: AutoScaleConfig,
            policies: Seq[AutoScalePolicy],
            groupManagerProvider: Provider[GroupManagement],
            schedulerProvider: Provider[MarathonSchedulerService],
            taskTracker: TaskTracker): Props = {
    Props(new AutoScaleActor(conf, policies, groupManagerProvider, schedulerProvider, taskTracker))
  }
  case object Tick
  case class GetAutoScaleApps(apps: Seq[AppDefinition])

  trait AutoScaleAppResult
  case class AutoScaleSuccess(app: AppDefinition) extends AutoScaleAppResult
  case class AutoScaleFailure(app: AppDefinition) extends AutoScaleAppResult
}

class AutoScaleActor(conf: AutoScaleConfig,
                     policies: Seq[AutoScalePolicy],
                     groupManagerProvider: Provider[GroupManagement],
                     schedulerProvider: Provider[MarathonSchedulerService],
                     taskTracker: TaskTracker) extends Actor with ActorLogging {

  implicit val ec = ThreadPoolContext.ioContext
  var groupManager: GroupManagement = _
  var scheduler: MarathonSchedulerService = _
  var tickScheduler: Option[Cancellable] = None
  var lastAppFailure: Map[PathId, Timestamp] = Map.empty

  override def preStart(): Unit = {
    log.info("Start AutoScaleActor")
    groupManager = groupManagerProvider.get()
    scheduler = schedulerProvider.get()
    tickScheduler = Some(context.system.scheduler.schedule(conf.initialDelay, conf.interval, self, Tick))
  }

  override def postStop(): Unit = {
    tickScheduler.foreach(_.cancel())
  }

  def triggerAutoScaleApps(): Unit = {
    groupManager.rootGroup().map { root =>
      val autoScaleApps = root.transitiveApps.filter(_.autoScale.isDefined).toSeq
      if (autoScaleApps.nonEmpty) self ! GetAutoScaleApps(autoScaleApps)
    }
  }

  def autoScaleAttempt(app: AppDefinition): Unit = {
    val failureOption = lastAppFailure.get(app.id)
    context.actorOf(
      AutoScaleAppActor.props(app, failureOption, self, policies, groupManager, scheduler, taskTracker, conf))
  }

  override def receive: Receive = {
    case Tick                   => triggerAutoScaleApps()
    case GetAutoScaleApps(apps) => apps.foreach(autoScaleAttempt)
    case AutoScaleSuccess(app)  => lastAppFailure -= app.id
    case AutoScaleFailure(app)  => if (!lastAppFailure.contains(app.id)) lastAppFailure += app.id -> Timestamp.now()
  }
}

