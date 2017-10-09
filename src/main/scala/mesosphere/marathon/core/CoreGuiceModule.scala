package mesosphere.marathon
package core

import java.time.Clock
import javax.inject.Named

import akka.actor.{ ActorRef, Props }
import akka.stream.Materializer
import com.google.inject._
import com.google.inject.name.Names
import com.typesafe.config.Config
import mesosphere.marathon.core.appinfo.{ AppInfoModule, AppInfoService, GroupInfoService, PodStatusService }
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.deployment.DeploymentManager
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.update.InstanceChangeHandler
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.{ LeadershipCoordinator, LeadershipModule }
import mesosphere.marathon.core.plugin.{ PluginDefinitions, PluginManager }
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ InstanceCreationHandler, InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.update.impl.steps._
import mesosphere.marathon.core.task.update.impl.{ TaskStatusUpdateProcessorImpl, ThrottlingTaskStatusUpdateProcessor }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.plugin.http.HttpRequestHandler
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.util.WorkQueue
import org.apache.mesos.Scheduler
import org.eclipse.jetty.servlets.EventSourceServlet

import scala.collection.immutable
import scala.concurrent.ExecutionContext

/**
  * Provides the glue between guice and the core modules.
  */
class CoreGuiceModule(config: Config) extends AbstractModule {
  @Provides @Singleton
  def provideConfig(): Config = config

  // Export classes used outside of core to guice
  @Provides @Singleton
  def electionService(coreModule: CoreModule): ElectionService = coreModule.electionModule.service

  @Provides @Singleton
  def leadershipModule(coreModule: CoreModule): LeadershipModule = coreModule.leadershipModule

  @Provides @Singleton
  def taskTracker(coreModule: CoreModule): InstanceTracker = coreModule.taskTrackerModule.instanceTracker

  @Provides @Singleton
  def taskKillService(coreModule: CoreModule): KillService = coreModule.taskTerminationModule.taskKillService

  @Provides @Singleton
  def taskCreationHandler(coreModule: CoreModule): InstanceCreationHandler =
    coreModule.taskTrackerModule.instanceCreationHandler

  @Provides @Singleton
  def stateOpProcessor(coreModule: CoreModule): TaskStateOpProcessor = coreModule.taskTrackerModule.stateOpProcessor

  @Provides @Singleton
  @SuppressWarnings(Array("UnusedMethodParameter"))
  def leadershipCoordinator( // linter:ignore UnusedParameter
    leadershipModule: LeadershipModule,
    // makeSureToInitializeThisBeforeCreatingCoordinator
    prerequisite1: TaskStatusUpdateProcessor,
    prerequisite2: LaunchQueue,
    prerequisite3: DeploymentManager): LeadershipCoordinator =
    leadershipModule.coordinator()

  @Provides @Singleton
  def offerProcessor(coreModule: CoreModule): OfferProcessor = coreModule.launcherModule.offerProcessor

  @Provides @Singleton
  def taskJobsModule(coreModule: CoreModule): TaskJobsModule = coreModule.taskJobsModule

  @Provides @Singleton
  final def launchQueue(coreModule: CoreModule): LaunchQueue = coreModule.appOfferMatcherModule.launchQueue

  @Provides @Singleton
  final def podStatusService(appInfoModule: AppInfoModule): PodStatusService = appInfoModule.podStatusService

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
  def readinessCheckExecutor(coreModule: CoreModule): ReadinessCheckExecutor =
    coreModule.readinessModule.readinessCheckExecutor

  @Provides
  @Singleton
  def materializer(coreModule: CoreModule): Materializer = coreModule.actorsModule.materializer

  @Provides
  @Singleton
  def providePersistenceStore(coreModule: CoreModule): PersistenceStore[_, _, _] = {
    coreModule.storageModule.persistenceStore
  }

  @Provides
  @Singleton
  def provideLeadershipInitializers(coreModule: CoreModule): immutable.Seq[PrePostDriverCallback] = {
    coreModule.storageModule.leadershipInitializers
  }

  @Provides
  @Singleton
  def deploymentRepository(coreModule: CoreModule): DeploymentRepository = coreModule.storageModule.deploymentRepository

  @Provides
  @Singleton
  def taskFailureRepository(coreModule: CoreModule): TaskFailureRepository =
    coreModule.storageModule.taskFailureRepository

  @Provides
  @Singleton
  def groupRepository(coreModule: CoreModule): GroupRepository =
    coreModule.storageModule.groupRepository

  @Provides @Singleton
  def frameworkIdRepository(coreModule: CoreModule): FrameworkIdRepository =
    coreModule.storageModule.frameworkIdRepository

  @Provides @Singleton
  def runtimeConfigurationRepository(coreModule: CoreModule): RuntimeConfigurationRepository =
    coreModule.storageModule.runtimeConfigurationRepository

  @Provides @Singleton
  def groupManager(coreModule: CoreModule): GroupManager = coreModule.groupManagerModule.groupManager

  @Provides @Singleton
  def podSystem(coreModule: CoreModule): PodManager = coreModule.podModule.podManager

  @Provides @Singleton
  def taskStatusUpdateSteps(
    notifyHealthCheckManagerStepImpl: NotifyHealthCheckManagerStepImpl,
    notifyRateLimiterStepImpl: NotifyRateLimiterStepImpl,
    notifyLaunchQueueStepImpl: NotifyLaunchQueueStepImpl,
    postToEventStreamStepImpl: PostToEventStreamStepImpl,
    scaleAppUpdateStepImpl: ScaleAppUpdateStepImpl): Seq[InstanceChangeHandler] = {

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
      ContinueOnErrorStep(postToEventStreamStepImpl),
      ContinueOnErrorStep(scaleAppUpdateStepImpl)
    )
  }

  @Provides @Singleton
  def pluginHttpRequestHandler(coreModule: CoreModule): Seq[HttpRequestHandler] = {
    coreModule.pluginModule.httpRequestHandler
  }

  override def configure(): Unit = {
    bind(classOf[Clock]).toInstance(Clock.systemUTC())
    bind(classOf[CoreModule]).to(classOf[CoreModuleImpl]).in(Scopes.SINGLETON)

    // FIXME: Because of cycle breaking in guice, it is hard to not wire it with Guice directly
    bind(classOf[TaskStatusUpdateProcessor])
      .annotatedWith(Names.named(ThrottlingTaskStatusUpdateProcessor.dependencyTag))
      .to(classOf[TaskStatusUpdateProcessorImpl]).asEagerSingleton()

    bind(classOf[TaskStatusUpdateProcessor]).to(classOf[ThrottlingTaskStatusUpdateProcessor]).asEagerSingleton()

    bind(classOf[AppInfoModule]).asEagerSingleton()
  }

  @Provides @Singleton @Named(ThrottlingTaskStatusUpdateProcessor.dependencyTag)
  def throttlingTaskStatusUpdateProcessorSerializer(config: MarathonConf): WorkQueue = {
    WorkQueue("TaskStatusUpdates", maxConcurrent = config.internalMaxParallelStatusUpdates(),
      maxQueueLength = config.internalMaxQueuedStatusUpdates())
  }

  @Provides
  @Singleton
  def provideExecutionContext: ExecutionContext = ExecutionContexts.global

  @Provides @Singleton @Named(ModuleNames.HISTORY_ACTOR_PROPS)
  def historyActor(coreModule: CoreModule): Props = coreModule.historyModule.historyActorProps

  @Provides @Singleton
  def httpEventStreamActor(coreModule: CoreModule): ActorRef = coreModule.eventModule.httpEventStreamActor

  @Provides @Singleton
  def httpEventStreamServlet(coreModule: CoreModule): EventSourceServlet = coreModule.eventModule.httpEventStreamServlet

  @Provides
  @Singleton
  def migration(coreModule: CoreModule): Migration = coreModule.storageModule.migration

  @Provides @Singleton
  def healthCheckManager(coreModule: CoreModule): HealthCheckManager = coreModule.healthModule.healthCheckManager

  @Provides
  @Singleton
  def deploymentManager(coreModule: CoreModule): DeploymentManager = coreModule.deploymentModule.deploymentManager

  @Provides
  @Singleton
  def schedulerActions(coreModule: CoreModule): SchedulerActions = coreModule.schedulerActions

  @Provides @Singleton
  def marathonScheduler(coreModule: CoreModule): MarathonScheduler = coreModule.marathonScheduler

  @Provides @Singleton
  def scheduler(coreModule: CoreModule): Scheduler = coreModule.mesosHeartbeatMonitor
}
