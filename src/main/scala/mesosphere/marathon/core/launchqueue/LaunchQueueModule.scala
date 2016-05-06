package mesosphere.marathon.core.launchqueue

import akka.actor.{ ActorRef, Props }
import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.launcher.TaskOpFactory
import mesosphere.marathon.core.launchqueue.impl.{
  AppTaskLauncherActor,
  LaunchQueueActor,
  LaunchQueueDelegate,
  RateLimiter,
  RateLimiterActor
}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.plugin.plugin.Opt
import mesosphere.marathon.plugin.optfactory._
import mesosphere.marathon.state.{ AppDefinition, AppRepository }
import org.apache.mesos.Protos.TaskInfo
import scala.reflect.ClassTag

/**
  * Provides a [[LaunchQueue]] implementation which can be used to launch tasks for a given AppDefinition.
  */
class LaunchQueueModule(
    config: LaunchQueueConfig,
    leadershipModule: LeadershipModule,
    clock: Clock,
    subOfferMatcherManager: OfferMatcherManager,
    maybeOfferReviver: Option[OfferReviver],
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskOpFactory: TaskOpFactory,
    pluginManager: Option[PluginManager] = None) {

  private[this] val launchQueueActorRef: ActorRef = {
    val props = LaunchQueueActor.props(config, appActorProps)
    leadershipModule.startWhenLeader(props, "launchQueue")
  }
  private[this] val rateLimiter: RateLimiter = new RateLimiter(clock)

  private[this] val rateLimiterActor: ActorRef = {
    val props = RateLimiterActor.props(
      rateLimiter, appRepository, launchQueueActorRef)
    leadershipModule.startWhenLeader(props, "rateLimiter")
  }

  val launchQueue: LaunchQueue = new LaunchQueueDelegate(config, launchQueueActorRef, rateLimiterActor)

  // TODO(jdef) copied this from auth.AuthModule; seems useful to have this as a reusable func
  private[this] def pluginOption[T](implicit ct: ClassTag[T]): Option[T] = {
    val noPlugins: Option[T] = None
    pluginManager.fold(noPlugins){ pm =>
      val plugins = pm.plugins[T]
      if (plugins.size > 1) throw new WrongConfigurationException(
        s"Only one plugin expected for ${ct.runtimeClass.getName}, but found: ${plugins.map(_.getClass.getName)}"
      )
      plugins.headOption
    }
  }

  private[this] lazy val appTaskInfoBuilderOptFactory = pluginOption[AppOptFactory[TaskInfo.Builder]]
    .getOrElse(AppOptFactory.noop)

  private[this] def appActorProps(app: AppDefinition, count: Int): Props =
    AppTaskLauncherActor.props(
      config,
      subOfferMatcherManager,
      clock,
      taskOpFactory,
      maybeOfferReviver,
      taskTracker,
      rateLimiterActor)(app, count)

  def optAppTaskInfoBuilder: Opt[TaskOpFactory.Config] = new Opt[TaskOpFactory.Config] {
    override def apply(c: TaskOpFactory.Config): Option[Opt[TaskOpFactory.Config]] = {
      c.optAppTaskInfoBuilder = AppOptFactory.combine(c.optAppTaskInfoBuilder, appTaskInfoBuilderOptFactory)
      None // no rollback for this
    }
  }

  taskOpFactory.withConfig(optAppTaskInfoBuilder)
}
