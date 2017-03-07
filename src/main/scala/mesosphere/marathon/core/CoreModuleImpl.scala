package mesosphere.marathon
package core

import javax.inject.Named

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.{ ActorsModule, Clock, ShutdownHooks }
import mesosphere.marathon.core.deployment.DeploymentModule
import mesosphere.marathon.core.election._
import mesosphere.marathon.core.event.EventModule
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.core.health.HealthModule
import mesosphere.marathon.core.history.HistoryModule
import mesosphere.marathon.core.instance.update.InstanceChangeHandler
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launcher.impl.UnreachableReservedOfferMonitor
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.util.StopOnFirstMatchingOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerModule
import mesosphere.marathon.core.matcher.reconcile.OfferMatcherReconciliationModule
import mesosphere.marathon.core.plugin.PluginModule
import mesosphere.marathon.core.pod.PodModule
import mesosphere.marathon.core.readiness.ReadinessModule
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.termination.TaskTerminationModule
import mesosphere.marathon.core.task.tracker.InstanceTrackerModule
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.storage.StorageModule

import scala.concurrent.ExecutionContext
import scala.util.Random

/**
  * Provides the wiring for the core module.
  *
  * Its parameters represent guice wired dependencies.
  * [[CoreGuiceModule]] exports some dependencies back to guice.
  */
class CoreModuleImpl @Inject() (
  // external dependencies still wired by guice
  marathonConf: MarathonConf,
  eventStream: EventStream,
  @Named(ModuleNames.HOST_PORT) hostPort: String,
  actorSystem: ActorSystem,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  clock: Clock,
  storage: StorageProvider,
  scheduler: Provider[DeploymentService],
  instanceUpdateSteps: Seq[InstanceChangeHandler],
  taskStatusUpdateProcessor: TaskStatusUpdateProcessor
)
    extends CoreModule {

  // INFRASTRUCTURE LAYER

  private[this] lazy val random = Random
  private[this] lazy val shutdownHookModule = ShutdownHooks()
  override lazy val actorsModule = new ActorsModule(shutdownHookModule, actorSystem)

  override lazy val leadershipModule = LeadershipModule(actorsModule.actorRefFactory)
  override lazy val electionModule = new ElectionModule(
    marathonConf,
    actorSystem,
    eventStream,
    hostPort,
    shutdownHookModule
  )

  // TASKS

  override lazy val taskBusModule = new TaskBusModule()
  override lazy val taskTrackerModule =
    new InstanceTrackerModule(clock, marathonConf, leadershipModule,
      storageModule.instanceRepository, instanceUpdateSteps)(actorsModule.materializer)
  override lazy val taskJobsModule = new TaskJobsModule(marathonConf, leadershipModule, clock)
  override lazy val storageModule = StorageModule(
    marathonConf)(
    actorsModule.materializer,
    ExecutionContext.global,
    actorSystem.scheduler,
    actorSystem)

  // READINESS CHECKS
  override lazy val readinessModule = new ReadinessModule(actorSystem)

  // this one can't be lazy right now because it wouldn't be instantiated soon enough ...
  override val taskTerminationModule = new TaskTerminationModule(
    taskTrackerModule, leadershipModule, marathonSchedulerDriverHolder, marathonConf, clock)

  // OFFER MATCHING AND LAUNCHING TASKS

  private[this] lazy val offerMatcherManagerModule = new OfferMatcherManagerModule(
    // infrastructure
    clock, random, marathonConf, actorSystem.scheduler,
    leadershipModule
  )

  private[this] lazy val offerMatcherReconcilerModule =
    new OfferMatcherReconciliationModule(
      marathonConf,
      clock,
      actorSystem.eventStream,
      taskTrackerModule.instanceTracker,
      storageModule.groupRepository,
      leadershipModule
    )

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    marathonConf,

    // external guicedependencies
    taskTrackerModule.instanceCreationHandler,
    marathonSchedulerDriverHolder,

    // internal core dependencies
    StopOnFirstMatchingOfferMatcher(
      offerMatcherReconcilerModule.offerMatcherReconciler,
      offerMatcherManagerModule.globalOfferMatcher
    ),
    pluginModule.pluginManager,
    offerStreamInput
  )(clock)

  lazy val offerStreamInput = UnreachableReservedOfferMonitor.run(
    lookupInstance = taskTrackerModule.instanceTracker.instance(_),
    taskStatusPublisher = taskStatusUpdateProcessor.publish(_)
  )(actorsModule.materializer)

  override lazy val appOfferMatcherModule = new LaunchQueueModule(
    marathonConf,
    leadershipModule, clock,

    // internal core dependencies
    offerMatcherManagerModule.subOfferMatcherManager,
    maybeOfferReviver,

    // external guice dependencies
    taskTrackerModule.instanceTracker,
    launcherModule.taskOpFactory
  )

  // PLUGINS

  override lazy val pluginModule = new PluginModule(marathonConf)

  override lazy val authModule: AuthModule = new AuthModule(pluginModule.pluginManager)

  // FLOW CONTROL GLUE

  private[this] lazy val flowActors = new FlowModule(leadershipModule)

  flowActors.refillOfferMatcherManagerLaunchTokens(
    marathonConf, taskBusModule.taskStatusObservables, offerMatcherManagerModule.subOfferMatcherManager)

  /** Combine offersWanted state from multiple sources. */
  private[this] lazy val offersWanted =
    offerMatcherManagerModule.globalOfferMatcherWantsOffers
      .combineLatest(offerMatcherReconcilerModule.offersWantedObservable)
      .map { case (managerWantsOffers, reconciliationWantsOffers) => managerWantsOffers || reconciliationWantsOffers }

  lazy val maybeOfferReviver = flowActors.maybeOfferReviver(
    clock, marathonConf,
    actorSystem.eventStream,
    offersWanted,
    marathonSchedulerDriverHolder)

  // EVENT

  override lazy val eventModule: EventModule = new EventModule(
    eventStream, actorSystem, marathonConf, clock, storageModule.eventSubscribersRepository,
    electionModule.service, authModule.authenticator, authModule.authorizer)

  // HISTORY

  override lazy val historyModule: HistoryModule =
    new HistoryModule(eventStream, storageModule.taskFailureRepository)

  // HEALTH CHECKS

  override lazy val healthModule: HealthModule = new HealthModule(
    actorSystem, taskTerminationModule.taskKillService, eventStream,
    taskTrackerModule.instanceTracker, groupManagerModule.groupManager)(actorsModule.materializer)

  // GROUP MANAGER

  override lazy val groupManagerModule: GroupManagerModule = new GroupManagerModule(
    marathonConf,
    scheduler,
    storageModule.groupRepository,
    storage)(ExecutionContext.global, eventStream)

  // PODS

  override lazy val podModule: PodModule = PodModule(groupManagerModule.groupManager)

  // DEPLOYMENT MANAGER

  override lazy val deploymentModule: DeploymentModule = new DeploymentModule(
    marathonConf,
    leadershipModule,
    taskTrackerModule.instanceTracker,
    taskTerminationModule.taskKillService,
    appOfferMatcherModule.launchQueue,
    schedulerActions, // alternatively schedulerActionsProvider.get()
    storage,
    healthModule.healthCheckManager,
    eventStream,
    readinessModule.readinessCheckExecutor,
    storageModule.deploymentRepository
  )(actorsModule.materializer)

  // GREEDY INSTANTIATION
  //
  // Greedily instantiate everything.
  //
  // lazy val allows us to write down object instantiations in any order.
  //
  // The LeadershipModule requires that all actors have been registered when the controller
  // is created. Changing the wiring order for this feels wrong since it is nicer if it
  // follows architectural logic. Therefore we instantiate them here explicitly.

  taskJobsModule.handleOverdueTasks(
    taskTrackerModule.instanceTracker,
    taskTrackerModule.stateOpProcessor,
    taskTerminationModule.taskKillService
  )
  taskJobsModule.expungeOverdueLostTasks(taskTrackerModule.instanceTracker, taskTrackerModule.stateOpProcessor)
  maybeOfferReviver
  offerMatcherManagerModule
  launcherModule
  offerMatcherReconcilerModule.start()
  eventModule
  historyModule
  healthModule
  podModule

  // The core (!) of the problem is that SchedulerActions are needed by MarathonModule::provideSchedulerActor
  // and CoreModule::deploymentModule. So until MarathonSchedulerActor is also a core component
  // and moved to CoreModules we can either:
  //
  // 1. Provide it in MarathonModule, inject as a constructor parameter here, in CoreModuleImpl and deal
  //    with Guice's "circular references involving constructors" e.g. by making it a Provider[SchedulerActions]
  //    to defer it's creation or:
  // 2. Create it here though it's not a core module and export it back via @Provider for MarathonModule
  //    to inject it in provideSchedulerActor(...) method.
  //
  // TODO: this can be removed when MarathonSchedulerActor becomes a core component
  override lazy val schedulerActions: SchedulerActions = new SchedulerActions(
    storageModule.groupRepository,
    healthModule.healthCheckManager,
    taskTrackerModule.instanceTracker,
    appOfferMatcherModule.launchQueue,
    eventStream,
    taskTerminationModule.taskKillService)(ExecutionContext.global)

}
