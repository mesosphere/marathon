package mesosphere.marathon.core

import javax.inject.{ Named, Provider }

import akka.actor.ActorRef
import akka.event.EventStream
import com.google.inject.name.Names
import com.google.inject.{ AbstractModule, Inject, Provides, Scopes, Singleton }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.CoreGuiceModule.TaskStatusUpdateActorProvider
import mesosphere.marathon.core.appinfo.{ AppInfoService, AppInfoModule }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.{ LeadershipCoordinator, LeadershipModule }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusObservables }
import mesosphere.marathon.core.task.tracker.TaskTrackerModule
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }

/**
  * Provides the glue between guice and the core modules.
  */
class CoreGuiceModule extends AbstractModule {

  // Export classes used outside of core to guice
  @Provides @Singleton
  def leadershipModule(coreModule: CoreModule): LeadershipModule = coreModule.leadershipModule

  @Provides @Singleton
  def leadershipCoordinator(
    leadershipModule: LeadershipModule,
    @Named("taskStatusUpdate") makeSureToInitializeThisBeforeCreatingCoordinator: ActorRef): LeadershipCoordinator =
    leadershipModule.coordinator()

  @Provides @Singleton
  def offerProcessor(coreModule: CoreModule): OfferProcessor = coreModule.launcherModule.offerProcessor

  @Provides @Singleton
  def taskStatusEmitter(coreModule: CoreModule): TaskStatusEmitter = coreModule.taskBusModule.taskStatusEmitter

  @Provides @Singleton
  def taskStatusObservable(coreModule: CoreModule): TaskStatusObservables =
    coreModule.taskBusModule.taskStatusObservables

  @Provides @Singleton
  def taskTrackerModule(coreModule: CoreModule): TaskTrackerModule =
    coreModule.taskTrackerModule

  @Provides @Singleton
  final def taskQueue(coreModule: CoreModule): LaunchQueue = coreModule.appOfferMatcherModule.taskQueue

  @Provides @Singleton
  final def appInfoService(appInfoModule: AppInfoModule): AppInfoService = appInfoModule.appInfoService

  @Provides @Singleton
  def pluginManager(coreModule: CoreModule): PluginManager = coreModule.pluginModule.pluginManager

  @Provides @Singleton
  def authorizer(coreModule: CoreModule): Authorizer = coreModule.authModule.authorizer

  @Provides @Singleton
  def authenticator(coreModule: CoreModule): Authenticator = coreModule.authModule.authenticator

  override def configure(): Unit = {
    bind(classOf[Clock]).toInstance(Clock())
    bind(classOf[CoreModule]).to(classOf[CoreModuleImpl]).in(Scopes.SINGLETON)
    bind(classOf[ActorRef])
      .annotatedWith(Names.named("taskStatusUpdate"))
      .toProvider(classOf[TaskStatusUpdateActorProvider])
      .asEagerSingleton()

    bind(classOf[AppInfoModule]).asEagerSingleton()
  }
}

object CoreGuiceModule {
  /** Break cyclic dependencies by using a provider here. */
  class TaskStatusUpdateActorProvider @Inject() (
      taskTrackerModule: TaskTrackerModule,
      taskStatusObservable: TaskStatusObservables,
      @Named(EventModule.busName) eventBus: EventStream,
      @Named("schedulerActor") schedulerActor: ActorRef,
      taskIdUtil: TaskIdUtil,
      healthCheckManager: HealthCheckManager,
      taskTracker: TaskTracker,
      marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder) extends Provider[ActorRef] {

    override def get(): ActorRef = {
      taskTrackerModule.processTaskStatusUpdates(
        taskStatusObservable = taskStatusObservable,
        eventBus = eventBus,
        schedulerActor = schedulerActor,
        taskIdUtil = taskIdUtil,
        healthCheckManager = healthCheckManager,
        taskTracker = taskTracker,
        marathonSchedulerDriverHolder = marathonSchedulerDriverHolder
      )
    }
  }
}
