package mesosphere.marathon.core

import javax.inject.Named

import akka.actor.ActorRefFactory
import com.google.inject.name.Names
import com.google.inject.{ AbstractModule, Provides, Scopes, Singleton }
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.appinfo.{ AppInfoModule, AppInfoService, GroupInfoService }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.{ LeadershipCoordinator, LeadershipModule }
import mesosphere.marathon.core.plugin.{ PluginDefinitions, PluginManager }
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskChangeObservables }
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.tracker.{ TaskCreationHandler, TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.core.task.update.impl.steps.{
  ContinueOnErrorStep,
  NotifyHealthCheckManagerStepImpl,
  NotifyLaunchQueueStepImpl,
  NotifyRateLimiterStepImpl,
  PostToEventStreamStepImpl,
  ScaleAppUpdateStepImpl,
  TaskStatusEmitterPublishStepImpl
}
import mesosphere.marathon.core.task.update.impl.{ TaskStatusUpdateProcessorImpl, ThrottlingTaskStatusUpdateProcessor }
import mesosphere.marathon.core.task.update.{ TaskStatusUpdateProcessor, TaskUpdateStep }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.plugin.http.HttpRequestHandler
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }

/**
  * Provides the glue between guice and the core modules.
  */
class CoreGuiceModule extends AbstractModule {

  // Export classes used outside of core to guice
  @Provides @Singleton
  def leadershipModule(coreModule: CoreModule): LeadershipModule = coreModule.leadershipModule

  @Provides @Singleton
  def taskTracker(coreModule: CoreModule): TaskTracker = coreModule.taskTrackerModule.taskTracker

  @Provides @Singleton
  def taskCreationHandler(coreModule: CoreModule): TaskCreationHandler =
    coreModule.taskTrackerModule.taskCreationHandler

  @Provides @Singleton
  def stateOpProcessor(coreModule: CoreModule): TaskStateOpProcessor = coreModule.taskTrackerModule.stateOpProcessor

  @Provides @Singleton
  def leadershipCoordinator(
    leadershipModule: LeadershipModule,
    // makeSureToInitializeThisBeforeCreatingCoordinator
    prerequisite1: TaskStatusUpdateProcessor,
    prerequisite2: LaunchQueue): LeadershipCoordinator =
    leadershipModule.coordinator()

  @Provides @Singleton
  def offerProcessor(coreModule: CoreModule): OfferProcessor = coreModule.launcherModule.offerProcessor

  @Provides @Singleton
  def taskStatusEmitter(coreModule: CoreModule): TaskStatusEmitter = coreModule.taskBusModule.taskStatusEmitter

  @Provides @Singleton
  def taskStatusObservable(coreModule: CoreModule): TaskChangeObservables =
    coreModule.taskBusModule.taskStatusObservables

  @Provides @Singleton
  def taskJobsModule(coreModule: CoreModule): TaskJobsModule = coreModule.taskJobsModule

  @Provides @Singleton
  final def launchQueue(coreModule: CoreModule): LaunchQueue = coreModule.appOfferMatcherModule.launchQueue

  @Provides @Singleton
  final def appInfoService(appInfoModule: AppInfoModule): AppInfoService = appInfoModule.appInfoService

  @Provides @Singleton
  final def groupInfoService(appInfoModule: AppInfoModule): GroupInfoService = appInfoModule.groupInfoService

  @Provides @Singleton
  def pluginManager(coreModule: CoreModule): PluginManager = coreModule.pluginModule.pluginManager

  @Provides @Singleton
  def pluginDefinitions(coreModule: CoreModule): PluginDefinitions = coreModule.pluginModule.pluginManager.definitions

  @Provides @Singleton
  def authorizer(coreModule: CoreModule): Authorizer = coreModule.authModule.authorizer

  @Provides @Singleton
  def authenticator(coreModule: CoreModule): Authenticator = coreModule.authModule.authenticator

  @Provides @Singleton
  def readinessCheckExecutor(coreModule: CoreModule): ReadinessCheckExecutor = coreModule.readinessModule.readinessCheckExecutor //scalastyle:ignore

  @Provides @Singleton
  def taskStatusUpdateSteps(
    notifyHealthCheckManagerStepImpl: NotifyHealthCheckManagerStepImpl,
    notifyRateLimiterStepImpl: NotifyRateLimiterStepImpl,
    notifyLaunchQueueStepImpl: NotifyLaunchQueueStepImpl,
    taskStatusEmitterPublishImpl: TaskStatusEmitterPublishStepImpl,
    postToEventStreamStepImpl: PostToEventStreamStepImpl,
    scaleAppUpdateStepImpl: ScaleAppUpdateStepImpl): Seq[TaskUpdateStep] = {

    // This is a sequence on purpose. The specified steps are executed in order for every
    // task status update.
    // This way we make sure that e.g. the taskTracker already reflects the changes for the update
    // (updateTaskTrackerStepImpl) before we notify the launch queue (notifyLaunchQueueStepImpl).

    // The task tracker is updated before any of these steps are processed.
    Seq(
      // Subsequent steps (for example, the health check subsystem) depend on
      // task tracker lookup to determine the routable host address for running
      // tasks.  In case this status update is the first TASK_RUNNING update
      // in IP-per-container mode, we need to store the assigned container
      // address reliably before attempting to initiate health checks, or
      // publish events to the bus.
      ContinueOnErrorStep(notifyHealthCheckManagerStepImpl),
      ContinueOnErrorStep(notifyRateLimiterStepImpl),
      ContinueOnErrorStep(notifyLaunchQueueStepImpl),
      ContinueOnErrorStep(taskStatusEmitterPublishImpl),
      ContinueOnErrorStep(postToEventStreamStepImpl),
      ContinueOnErrorStep(scaleAppUpdateStepImpl)
    )
  }

  @Provides @Singleton
  def pluginHttpRequestHandler(coreModule: CoreModule): Seq[HttpRequestHandler] = {
    coreModule.pluginModule.httpRequestHandler
  }

  override def configure(): Unit = {
    bind(classOf[Clock]).toInstance(Clock())
    bind(classOf[CoreModule]).to(classOf[CoreModuleImpl]).in(Scopes.SINGLETON)

    // FIXME: Because of cycle breaking in guice, it is hard to not wire it with Guice directly
    bind(classOf[TaskStatusUpdateProcessor])
      .annotatedWith(Names.named(ThrottlingTaskStatusUpdateProcessor.dependencyTag))
      .to(classOf[TaskStatusUpdateProcessorImpl]).asEagerSingleton()

    bind(classOf[TaskStatusUpdateProcessor]).to(classOf[ThrottlingTaskStatusUpdateProcessor]).asEagerSingleton()

    bind(classOf[AppInfoModule]).asEagerSingleton()
  }

  @Provides @Singleton @Named(ThrottlingTaskStatusUpdateProcessor.dependencyTag)
  def throttlingTaskStatusUpdateProcessorSerializer(
    metrics: Metrics,
    config: MarathonConf,
    actorRefFactory: ActorRefFactory): CapConcurrentExecutions = {
    val capMetrics = new CapConcurrentExecutionsMetrics(metrics, classOf[ThrottlingTaskStatusUpdateProcessor])

    CapConcurrentExecutions(
      capMetrics,
      actorRefFactory,
      "serializeTaskStatusUpdates",
      maxParallel = config.internalMaxParallelStatusUpdates(),
      maxQueued = config.internalMaxQueuedStatusUpdates()
    )
  }
}
