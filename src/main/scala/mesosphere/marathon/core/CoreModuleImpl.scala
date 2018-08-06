package mesosphere.marathon
package core

import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import java.time.Clock
import java.util.concurrent.Executors

import javax.inject.Named
import akka.actor.{ActorRef, ActorSystem}
import akka.event.EventStream
import akka.http.scaladsl.model.Uri
import com.google.inject.{Inject, Provider}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.{ActorsModule, JvmExitsCrashStrategy, LifecycleState}
import mesosphere.marathon.core.deployment.DeploymentModule
import mesosphere.marathon.core.election._
import mesosphere.marathon.core.event.EventModule
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.group.{GroupManagerConfig, GroupManagerModule}
import mesosphere.marathon.core.health.HealthModule
import mesosphere.marathon.core.heartbeat.MesosHeartbeatMonitor
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
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.termination.TaskTerminationModule
import mesosphere.marathon.core.task.tracker.InstanceTrackerModule
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.storage.{StorageConf, StorageModule}
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.util.NamedExecutionContext
import mesosphere.util.state.MesosLeaderInfo

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
    scheduler: Provider[DeploymentService],
    instanceUpdateSteps: Seq[InstanceChangeHandler],
    taskStatusUpdateProcessor: TaskStatusUpdateProcessor,
    mesosLeaderInfo: MesosLeaderInfo,
    @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR) heartbeatActor: ActorRef
)
  extends CoreModule with StrictLogging {

  // INFRASTRUCTURE LAYER

  private[this] lazy val random = Random
  private[this] lazy val lifecycleState = LifecycleState.WatchingJVM
  override lazy val actorsModule = new ActorsModule(actorSystem)
  private[this] lazy val crashStrategy = JvmExitsCrashStrategy
  private[this] val electionExecutor = Executors.newSingleThreadExecutor()

  override lazy val config = {
    //
    // Create Kamon configuration spec for the `kamon.datadog` plugin
    //
    val datadog = marathonConf.dataDog.toOption.map { urlStr =>
      val url = Uri(urlStr)
      val params = url.query()

      (List(
        Some("kamon.datadog.hostname" -> url.authority.host),
        Some("kamon.datadog.port" -> (if (url.authority.port == 0) 8125 else url.authority.port)),
        Some("kamon.datadog.flush-interval" -> s"${params.collectFirst { case ("interval", iv) => iv.toInt }.getOrElse(10)} seconds")
      ) ++ params.map {
          case ("prefix", value) => Some("kamon.datadog.application-name" -> value)
          case ("tags", value) => Some("kamon.datadog.global-tags" -> value)
          case ("max-packet-size", value) => Some("kamon.datadog.max-packet-size" -> value)
          case ("api-key", value) => Some("kamon.datadog.http.api-key" -> value)
          case ("interval", _) => None // (This case used only to account this as 'known' parameter)
          case (name, _) => {
            logger.warn(s"Datadog reporter parameter `${name}` is unknown and ignored")
            None
          }
        }).flatten.toMap
    }

    //
    // Create Kamon configuration spec for the `kamon.statsd` plugin
    //
    val statsd = marathonConf.graphite.toOption.map { urlStr =>
      val url = Uri(urlStr)
      val params = url.query()

      if (url.scheme.toLowerCase() != "udp") {
        logger.warn(s"Graphite reporter protocol ${url.scheme} is not supported; using UDP")
      }

      (List(
        Some("kamon.statsd.hostname" -> url.authority.host),
        Some("kamon.statsd.port" -> (if (url.authority.port == 0) 8125 else url.authority.port)),
        Some("kamon.statsd.flush-interval" -> s"${params.collectFirst { case ("interval", iv) => iv.toInt }.getOrElse(10)} seconds")
      ) ++ params.map {
          case ("prefix", value) => Some("kamon.statsd.simple-metric-key-generator.application" -> value)
          case ("hostname", value) => Some("kamon.statsd.simple-metric-key-generator.hostname-override" -> value)
          case ("interval", _) => None // (This case used only to account this as 'known' parameter)
          case (name, _) => {
            logger.warn(s"Statsd reporter parameter `${name}` is unknown and ignored")
            None
          }
        }).flatten.toMap
    }

    // Stringify the map
    val values = datadog.getOrElse(Map()) ++ statsd.getOrElse(Map())
    val overrides = ConfigFactory.parseString(values.map(kv => s"${kv._1}: ${kv._2}").mkString("\n"))
    overrides.withFallback(ConfigFactory.load())
  }

  override lazy val metricsModule = MetricsModule(marathonConf, config)
  override lazy val leadershipModule = LeadershipModule(actorsModule.actorRefFactory)
  override lazy val electionModule = new ElectionModule(
    metricsModule.metrics,
    marathonConf,
    actorSystem,
    eventStream,
    hostPort,
    crashStrategy,
    ExecutionContext.fromExecutor(electionExecutor)
  )

  // TASKS
  val storageExecutionContext = NamedExecutionContext.fixedThreadPoolExecutionContext(marathonConf.asInstanceOf[StorageConf].storageExecutionContextSize(), "storage-module")
  override lazy val instanceTrackerModule =
    new InstanceTrackerModule(metricsModule.metrics, clock, marathonConf, leadershipModule,
      storageModule.instanceRepository, instanceUpdateSteps)(actorsModule.materializer)
  override lazy val taskJobsModule = new TaskJobsModule(marathonConf, leadershipModule, marathonSchedulerDriverHolder, clock)
  override lazy val storageModule = StorageModule(
    metricsModule.metrics,
    marathonConf,
    lifecycleState)(
      actorsModule.materializer,
      storageExecutionContext,
      actorSystem.scheduler,
      actorSystem)

  // READINESS CHECKS
  override lazy val readinessModule = new ReadinessModule(actorSystem, actorsModule.materializer)

  // this one can't be lazy right now because it wouldn't be instantiated soon enough ...
  override val taskTerminationModule = new TaskTerminationModule(
    instanceTrackerModule, leadershipModule, marathonSchedulerDriverHolder, marathonConf, clock)

  // OFFER MATCHING AND LAUNCHING TASKS

  private[this] lazy val offerMatcherManagerModule = new OfferMatcherManagerModule(
    // infrastructure
    metricsModule.metrics,
    clock, random, marathonConf,
    leadershipModule,
    () => marathonScheduler.getLocalRegion
  )(actorsModule.materializer)

  private[this] lazy val offerMatcherReconcilerModule =
    new OfferMatcherReconciliationModule(
      marathonConf,
      clock,
      actorSystem.eventStream,
      instanceTrackerModule.instanceTracker,
      storageModule.groupRepository,
      leadershipModule
    )(actorsModule.materializer)

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    metricsModule.metrics,
    marathonConf,

    // external guicedependencies
    instanceTrackerModule.instanceTracker,
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
    instanceTrackerModule.instanceTracker,
    launcherModule.taskOpFactory,
    () => marathonScheduler.getLocalRegion
  )

  // PLUGINS
  override lazy val pluginModule = new PluginModule(marathonConf, crashStrategy)

  override lazy val authModule: AuthModule = new AuthModule(pluginModule.pluginManager)

  // FLOW CONTROL GLUE

  private[this] lazy val flowActors = new FlowModule(leadershipModule)

  flowActors.refillOfferMatcherManagerLaunchTokens(
    marathonConf, offerMatcherManagerModule.subOfferMatcherManager)

  /** Combine offersWanted state from multiple sources. */
  private[this] lazy val offersWanted: Source[Boolean, Cancellable] = {
    offerMatcherManagerModule.globalOfferMatcherWantsOffers
      .via(EnrichedFlow.combineLatest(offerMatcherReconcilerModule.offersWantedObservable, eagerComplete = true))
      .map { case (managerWantsOffers, reconciliationWantsOffers) => managerWantsOffers || reconciliationWantsOffers }
  }

  lazy val maybeOfferReviver = flowActors.maybeOfferReviver(
    metricsModule.metrics,
    clock, marathonConf,
    actorSystem.eventStream,
    offersWanted,
    marathonSchedulerDriverHolder)

  // EVENT

  override lazy val eventModule: EventModule = new EventModule(
    metricsModule.metrics, eventStream, actorSystem, marathonConf, marathonConf.deprecatedFeatures(),
    electionModule.service, authModule.authenticator, authModule.authorizer)(actorsModule.materializer)

  // HISTORY

  override lazy val historyModule: HistoryModule =
    new HistoryModule(eventStream, storageModule.taskFailureRepository)

  // HEALTH CHECKS

  override lazy val healthModule: HealthModule = new HealthModule(
    actorSystem, taskTerminationModule.taskKillService, eventStream,
    instanceTrackerModule.instanceTracker, groupManagerModule.groupManager)(actorsModule.materializer)

  // GROUP MANAGER

  val groupManagerExecutionContext = NamedExecutionContext.fixedThreadPoolExecutionContext(marathonConf.asInstanceOf[GroupManagerConfig].groupManagerExecutionContextSize(), "group-manager-module")
  override lazy val groupManagerModule: GroupManagerModule = new GroupManagerModule(
    metricsModule.metrics,
    marathonConf,
    scheduler,
    storageModule.groupRepository)(groupManagerExecutionContext, eventStream, authModule.authorizer)

  // PODS

  override lazy val podModule: PodModule = PodModule(groupManagerModule.groupManager)

  // DEPLOYMENT MANAGER

  override lazy val deploymentModule: DeploymentModule = new DeploymentModule(
    metricsModule.metrics,
    marathonConf,
    leadershipModule,
    instanceTrackerModule.instanceTracker,
    taskTerminationModule.taskKillService,
    appOfferMatcherModule.launchQueue,
    schedulerActions, // alternatively schedulerActionsProvider.get()
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

  metricsModule.start(actorSystem)
  taskJobsModule.handleOverdueTasks(
    instanceTrackerModule.instanceTracker,
    taskTerminationModule.taskKillService
  )
  taskJobsModule.expungeOverdueLostTasks(instanceTrackerModule.instanceTracker)
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

  val schedulerActionsExecutionContext = NamedExecutionContext.fixedThreadPoolExecutionContext(marathonConf.asInstanceOf[MarathonSchedulerServiceConfig].schedulerActionsExecutionContextSize(), "scheduler-actions")
  override lazy val schedulerActions: SchedulerActions = new SchedulerActions(
    storageModule.groupRepository,
    healthModule.healthCheckManager,
    instanceTrackerModule.instanceTracker,
    appOfferMatcherModule.launchQueue,
    eventStream,
    taskTerminationModule.taskKillService)(schedulerActionsExecutionContext)

  override lazy val marathonScheduler: MarathonScheduler = new MarathonScheduler(eventStream, launcherModule.offerProcessor, taskStatusUpdateProcessor, storageModule.frameworkIdRepository, mesosLeaderInfo, marathonConf)

  // MesosHeartbeatMonitor decorates MarathonScheduler
  override def mesosHeartbeatMonitor = new MesosHeartbeatMonitor(marathonScheduler, heartbeatActor)
}
