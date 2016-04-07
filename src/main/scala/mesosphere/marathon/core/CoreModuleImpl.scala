package mesosphere.marathon.core

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.{ ActorsModule, Clock, ShutdownHooks }
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.launcher.{ TaskOpFactory, LauncherModule }
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.util.StopOnFirstMatchingOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerModule
import mesosphere.marathon.core.matcher.reconcile.OfferMatcherReconciliationModule
import mesosphere.marathon.core.plugin.PluginModule
import mesosphere.marathon.core.readiness.ReadinessModule
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.tracker.TaskTrackerModule
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ GroupRepository, AppRepository, TaskRepository }
import mesosphere.marathon.{ LeadershipAbdication, MarathonConf, MarathonSchedulerDriverHolder }

import scala.util.Random

/**
  * Provides the wiring for the core module.
  *
  * Its parameters represent guice wired dependencies.
  * [[CoreGuiceModule]] exports some dependencies back to guice.
  */
class CoreModuleImpl @Inject() (
    // external dependencies still wired by guice
    zk: ZooKeeperClient,
    leader: LeadershipAbdication,
    marathonConf: MarathonConf,
    metrics: Metrics,
    actorSystem: ActorSystem,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    appRepository: AppRepository,
    groupRepository: GroupRepository,
    taskRepository: TaskRepository,
    taskOpFactory: TaskOpFactory,
    leaderInfo: LeaderInfo,
    clock: Clock,
    taskStatusUpdateSteps: Seq[TaskUpdateStep]) extends CoreModule {

  // INFRASTRUCTURE LAYER

  private[this] lazy val random = Random
  private[this] lazy val shutdownHookModule = ShutdownHooks()
  private[this] lazy val actorsModule = new ActorsModule(shutdownHookModule, actorSystem)

  override lazy val leadershipModule = LeadershipModule(actorsModule.actorRefFactory, zk, leader)

  // TASKS

  override lazy val taskBusModule = new TaskBusModule()
  override lazy val taskTrackerModule =
    new TaskTrackerModule(clock, metrics, marathonConf, leadershipModule, taskRepository, taskStatusUpdateSteps)
  override lazy val taskJobsModule = new TaskJobsModule(marathonConf, leadershipModule, clock)

  // READINESS CHECKS
  lazy val readinessModule = new ReadinessModule(actorSystem)

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
      taskTrackerModule.taskTracker,
      groupRepository,
      offerMatcherManagerModule.subOfferMatcherManager,
      leadershipModule
    )

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    clock, metrics, marathonConf,

    // external guicedependencies
    taskTrackerModule.taskCreationHandler,
    marathonSchedulerDriverHolder,

    // internal core dependencies
    StopOnFirstMatchingOfferMatcher(
      offerMatcherReconcilerModule.offerMatcherReconciler,
      offerMatcherManagerModule.globalOfferMatcher
    )
  )

  override lazy val appOfferMatcherModule = new LaunchQueueModule(
    marathonConf,
    leadershipModule, clock,

    // internal core dependencies
    offerMatcherManagerModule.subOfferMatcherManager,
    maybeOfferReviver,

    // external guice dependencies
    appRepository,
    taskTrackerModule.taskTracker,
    taskOpFactory
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
    taskTrackerModule.taskTracker,
    taskTrackerModule.taskReservationTimeoutHandler,
    marathonSchedulerDriverHolder
  )
  maybeOfferReviver
  offerMatcherManagerModule
  launcherModule
  offerMatcherReconcilerModule.start()
}
