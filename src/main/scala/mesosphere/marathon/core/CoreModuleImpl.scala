package mesosphere.marathon.core

import akka.actor.ActorSystem
import com.google.inject.Inject
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.base.{ ActorsModule, Clock, ShutdownHooks }
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerModule
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.tracker.TaskTrackerModule
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskFactory, TaskTracker }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }

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
    metrics: Metrics,
    actorSystem: ActorSystem,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskFactory: TaskFactory,
    leaderInfo: LeaderInfo) extends CoreModule {

  // INFRASTRUCTURE LAYER

  override lazy val clock = Clock()
  private[this] lazy val random = Random
  private[this] lazy val shutdownHookModule = ShutdownHooks()
  private[this] lazy val actorsModule = new ActorsModule(shutdownHookModule, actorSystem)

  lazy val leadershipModule = new LeadershipModule(actorsModule.actorRefFactory)

  // TASKS

  override lazy val taskBusModule = new TaskBusModule()
  override lazy val taskTrackerModule = new TaskTrackerModule(leadershipModule)

  // OFFER MATCHING AND LAUNCHING TASKS

  private[this] lazy val offerMatcherManagerModule = new OfferMatcherManagerModule(
    // infrastructure
    clock, random, metrics, marathonConf,
    leadershipModule
  )

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    clock, metrics, marathonConf,

    // external guicedependencies
    marathonSchedulerDriverHolder,

    // internal core dependencies
    offerMatcherManagerModule.globalOfferMatcher)

  override lazy val appOfferMatcherModule = new LaunchQueueModule(
    marathonConf,
    leadershipModule, clock,

    // internal core dependencies
    offerMatcherManagerModule.subOfferMatcherManager,
    taskBusModule.taskStatusObservables,
    maybeOfferReviver,

    // external guice dependencies
    appRepository,
    taskTracker,
    taskFactory
  )

  // FLOW CONTROL GLUE

  private[this] lazy val flowActors = new FlowModule(leadershipModule)

  flowActors.refillOfferMatcherManagerLaunchTokens(
    marathonConf, taskBusModule.taskStatusObservables, offerMatcherManagerModule.subOfferMatcherManager)

  lazy val maybeOfferReviver = flowActors.maybeOfferReviver(
    clock, marathonConf,
    actorSystem.eventStream,
    offerMatcherManagerModule.globalOfferMatcherWantsOffers, marathonSchedulerDriverHolder)

  // GREEDY INSTANTIATION
  //
  // Greedily instantiate everything.
  //
  // lazy val allows us to write down object instantiations in any order.
  //
  // The LeadershipModule requires that all actors have been registered when the controller
  // is created. Changing the wiring order for this feels wrong since it is nicer if it
  // follows architectural logic. Therefore we instantiate them here explicitly.

  taskTrackerModule.killOverdueTasks(taskTracker, marathonSchedulerDriverHolder)
  maybeOfferReviver
  offerMatcherManagerModule
  launcherModule
}
