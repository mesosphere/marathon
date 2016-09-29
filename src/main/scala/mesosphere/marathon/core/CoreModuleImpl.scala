package mesosphere.marathon.core

import javax.inject.Named

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.{ ActorsModule, Clock, ShutdownHooks }
import mesosphere.marathon.core.election._
import mesosphere.marathon.core.event.EventModule
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.core.health.HealthModule
import mesosphere.marathon.core.history.HistoryModule
import mesosphere.marathon.core.instance.update.InstanceChangeHandler
import mesosphere.marathon.core.launcher.LauncherModule
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
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.StorageModule
import mesosphere.marathon.{ DeploymentService, MarathonConf, MarathonSchedulerDriverHolder, ModuleNames }
import mesosphere.util.CapConcurrentExecutions

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
  metrics: Metrics,
  actorSystem: ActorSystem,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  clock: Clock,
  storage: StorageProvider,
  scheduler: Provider[DeploymentService],
  @Named(ModuleNames.SERIALIZE_GROUP_UPDATES) serializeUpdates: CapConcurrentExecutions,
  instanceUpdateSteps: Seq[InstanceChangeHandler])
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
    metrics,
    hostPort,
    shutdownHookModule
  )

  // TASKS

  override lazy val taskBusModule = new TaskBusModule()
  override lazy val taskTrackerModule =
    new InstanceTrackerModule(clock, metrics, marathonConf, leadershipModule,
      storageModule.instanceRepository, instanceUpdateSteps)(actorsModule.materializer)
  override lazy val taskJobsModule = new TaskJobsModule(marathonConf, leadershipModule, clock)
  override lazy val storageModule = StorageModule(
    marathonConf)(
    metrics,
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
    clock, random, metrics, marathonConf,
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
    metrics, marathonConf,

    // external guicedependencies
    taskTrackerModule.instanceCreationHandler,
    marathonSchedulerDriverHolder,

    // internal core dependencies
    StopOnFirstMatchingOfferMatcher(
      offerMatcherReconcilerModule.offerMatcherReconciler,
      offerMatcherManagerModule.globalOfferMatcher
    ),
    pluginModule.pluginManager
  )(clock)

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
    eventStream, actorSystem, marathonConf, metrics, clock, storageModule.eventSubscribersRepository,
    electionModule.service, authModule.authenticator, authModule.authorizer)

  // HISTORY

  override lazy val historyModule: HistoryModule =
    new HistoryModule(eventStream, storageModule.taskFailureRepository)

  // HEALTH CHECKS

  override lazy val healthModule: HealthModule = new HealthModule(
    actorSystem, taskTerminationModule.taskKillService, eventStream,
    taskTrackerModule.instanceTracker, storageModule.appRepository)

  // GROUP MANAGER

  override lazy val groupManagerModule: GroupManagerModule = new GroupManagerModule(
    marathonConf,
    leadershipModule,
    serializeUpdates,
    scheduler,
    storageModule.groupRepository,
    storage,
    eventStream,
    metrics)(actorsModule.materializer)

  // PODS

  override lazy val podModule: PodModule =
    PodModule(groupManagerModule.groupManager, storageModule.podRepository)(ExecutionContext.global)

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
    taskTrackerModule.instanceReservationTimeoutHandler,
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
}
