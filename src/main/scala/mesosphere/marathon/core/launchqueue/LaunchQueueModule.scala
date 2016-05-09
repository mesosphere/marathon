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
import mesosphere.marathon.plugin.plugin
import mesosphere.marathon.plugin.{ AppDefinition => PluginAppDefinition }
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

  private[this] def pluginOptions[T <: plugin.Opt.Factory[_, _]](implicit ct: ClassTag[T]): Seq[T] = {
    pluginManager.fold(Seq.empty[T]){ pm => pm.plugins[T] }
  }

  private[this] def appTaskInfoBuilderOptFactories =
    pluginOptions[plugin.Opt.Factory.Plugin[PluginAppDefinition, TaskInfo.Builder]]

  private[this] def appActorProps(app: AppDefinition, count: Int): Props =
    AppTaskLauncherActor.props(
      config,
      subOfferMatcherManager,
      clock,
      taskOpFactory,
      maybeOfferReviver,
      taskTracker,
      rateLimiterActor)(app, count)

  def optAppTaskInfoBuilder: plugin.Opt[TaskOpFactory.Config] = new plugin.Opt[TaskOpFactory.Config] {
    override def apply(c: TaskOpFactory.Config): Option[plugin.Opt[TaskOpFactory.Config]] = {
      c.optAppTaskInfoBuilder = plugin.Opt.Factory.combine(appTaskInfoBuilderOptFactories: _*)
      None // no rollback for this
    }
  }

  taskOpFactory.withConfig(optAppTaskInfoBuilder)
}
