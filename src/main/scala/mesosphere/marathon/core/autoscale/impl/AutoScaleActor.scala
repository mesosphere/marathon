package mesosphere.marathon.core.autoscale.impl

import javax.inject.Provider

import akka.actor._
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.autoscale.AutoScaleConfig
import mesosphere.marathon.core.autoscale.impl.AutoScaleActor._
import mesosphere.marathon.state._

object AutoScaleActor {

  def props(conf: AutoScaleConfig,
            groupManagerProvider: Provider[GroupManager],
            schedulerProvider: Provider[MarathonSchedulerService],
            appActorFactory: (AppDefinition, Option[Timestamp], ActorRef) => Props): Props = {
    Props(new AutoScaleActor(conf, groupManagerProvider, schedulerProvider, appActorFactory))
  }
  case object Tick
  case class AutoScaleApps(apps: Seq[AppDefinition])

  trait AutoScaleAppResult
  case class AutoScaleSuccess(app: AppDefinition) extends AutoScaleAppResult
  case class AutoScaleFailure(app: AppDefinition) extends AutoScaleAppResult
}

class AutoScaleActor(conf: AutoScaleConfig,
                     groupManagerProvider: Provider[GroupManager],
                     schedulerProvider: Provider[MarathonSchedulerService],
                     appActorFactory: (AppDefinition, Option[Timestamp], ActorRef) => Props)
    extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  var groupManager: GroupManager = _
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
      if (autoScaleApps.nonEmpty) self ! AutoScaleApps(autoScaleApps)
    }
  }

  def autoScaleAttempt(app: AppDefinition): Unit = {
    context.actorOf(appActorFactory(app, lastAppFailure.get(app.id), self))
  }

  override def receive: Receive = {
    case Tick                  => triggerAutoScaleApps()
    case AutoScaleApps(apps)   => apps.foreach(autoScaleAttempt)
    case AutoScaleSuccess(app) => lastAppFailure -= app.id
    case AutoScaleFailure(app) => if (!lastAppFailure.contains(app.id)) lastAppFailure += app.id -> Timestamp.now()
  }
}

