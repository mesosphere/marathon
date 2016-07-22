package mesosphere.marathon

// scalastyle:off
import java.util.concurrent.TimeUnit
import javax.inject.Named

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.event.EventStream
import akka.routing.RoundRobinPool
import akka.stream.Materializer
import com.codahale.metrics.Gauge
import com.google.inject._
import com.google.inject.name.Names
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.storage.repository.{ DeploymentRepository, GroupRepository, ReadOnlyAppRepository, TaskFailureRepository }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.{ HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentManager
import mesosphere.util.state._
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }
import org.apache.mesos.Scheduler
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
// scalastyle:on

object ModuleNames {
  final val HOST_PORT = "HOST_PORT"

  final val SERVER_SET_PATH = "SERVER_SET_PATH"
  final val SERIALIZE_GROUP_UPDATES = "SERIALIZE_GROUP_UPDATES"
  final val HISTORY_ACTOR_PROPS = "HISTORY_ACTOR_PROPS"

  final val STORE_APP = "AppStore"
  final val STORE_TASK_FAILURES = "TaskFailureStore"
  final val STORE_DEPLOYMENT_PLAN = "DeploymentPlanStore"
  final val STORE_FRAMEWORK_ID = "FrameworkIdStore"
  final val STORE_GROUP = "GroupStore"
  final val STORE_TASK = "TaskStore"
  final val STORE_EVENT_SUBSCRIBERS = "EventSubscriberStore"

  final val MESOS_HEARTBEAT_ACTOR = "MesosHeartbeatActor"
}

class MarathonModule(conf: MarathonConf, http: HttpConf)
    extends AbstractModule {

  val log = LoggerFactory.getLogger(getClass.getName)

  def configure() {
    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[LeaderProxyConf]).toInstance(conf)
    bind(classOf[ZookeeperConf]).toInstance(conf)

    // MesosHeartbeatMonitor decorates MarathonScheduler
    bind(classOf[Scheduler]).to(classOf[MesosHeartbeatMonitor]).in(Scopes.SINGLETON)
    bind(classOf[Scheduler])
      .annotatedWith(Names.named(MesosHeartbeatMonitor.BASE))
      .to(classOf[MarathonScheduler])
      .in(Scopes.SINGLETON)

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[HealthCheckManager]).to(classOf[MarathonHealthCheckManager]).in(Scopes.SINGLETON)

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)

    bind(classOf[Metrics]).in(Scopes.SINGLETON)
  }

  @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR)
  @Provides
  @Singleton
  def provideMesosHeartbeatActor(system: ActorSystem): ActorRef = {
    system.actorOf(Heartbeat.props(Heartbeat.Config(
      FiniteDuration(conf.mesosHeartbeatInterval.get.getOrElse(
        MesosHeartbeatMonitor.DEFAULT_HEARTBEAT_INTERVAL_MS), TimeUnit.MILLISECONDS),
      conf.mesosHeartbeatFailureThreshold.get.getOrElse(MesosHeartbeatMonitor.DEFAULT_HEARTBEAT_FAILURE_THRESHOLD)
    )), ModuleNames.MESOS_HEARTBEAT_ACTOR)
  }

  @Provides
  @Singleton
  def provideMesosLeaderInfo(): MesosLeaderInfo = {
    conf.mesosLeaderUiUrl.get match {
      case someUrl @ Some(_) => ConstMesosLeaderInfo(someUrl)
      case None => new MutableMesosLeaderInfo
    }
  }

  // TODO: Jason - Old storage won't work without being wired in here somehow...
  // same if we're using a load time caching store. Maybe StorageModule should expose a Seq of its own?
  @Provides
  @Singleton
  def provideLeadershipInitializers(): Seq[PrePostDriverCallback] = {
    Nil
  }

  //scalastyle:off parameter.number method.length
  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  def provideSchedulerActor(
    system: ActorSystem,
    appRepository: ReadOnlyAppRepository,
    groupRepository: GroupRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    launchQueue: LaunchQueue,
    driverHolder: MarathonSchedulerDriverHolder,
    electionService: ElectionService,
    storage: StorageProvider,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    taskFailureRepository: TaskFailureRepository,
    @Named(ModuleNames.HISTORY_ACTOR_PROPS) historyActorProps: Props)(implicit mat: Materializer): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    import scala.concurrent.ExecutionContext.Implicits.global

    def createSchedulerActions(schedulerActor: ActorRef): SchedulerActions = {
      new SchedulerActions(
        appRepository,
        groupRepository,
        healthCheckManager,
        taskTracker,
        launchQueue,
        eventBus,
        schedulerActor,
        conf)
    }

    def deploymentManagerProps(schedulerActions: SchedulerActions): Props = {
      Props(
        new DeploymentManager(
          appRepository,
          taskTracker,
          launchQueue,
          schedulerActions,
          storage,
          healthCheckManager,
          eventBus,
          readinessCheckExecutor,
          conf
        )
      )
    }

    system.actorOf(
      MarathonSchedulerActor.props(
        createSchedulerActions,
        deploymentManagerProps,
        historyActorProps,
        appRepository,
        deploymentRepository,
        healthCheckManager,
        taskTracker,
        launchQueue,
        driverHolder,
        electionService,
        eventBus,
        conf
      ).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.HOST_PORT)
  @Provides
  @Singleton
  def provideHostPort: String = {
    val port = if (http.disableHttp()) http.httpsPort() else http.httpPort()
    "%s:%d".format(conf.hostname(), port)
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = ActorSystem("marathon")

  /* Reexports the `akka.actor.ActorSystem` as `akka.actor.ActorRefFactory`. It doesn't work automatically. */
  @Provides
  @Singleton
  def provideActorRefFactory(system: ActorSystem): ActorRefFactory = system

  @Provides
  @Singleton
  def provideStorageProvider(http: HttpConf): StorageProvider =
    StorageProvider.provider(conf, http)

  @Named(ModuleNames.SERIALIZE_GROUP_UPDATES)
  @Provides
  @Singleton
  def provideSerializeGroupUpdates(metrics: Metrics, actorRefFactory: ActorRefFactory): CapConcurrentExecutions = {
    val capMetrics = new CapConcurrentExecutionsMetrics(metrics, classOf[GroupManager])
    CapConcurrentExecutions(
      capMetrics,
      actorRefFactory,
      "serializeGroupUpdates",
      maxConcurrent = 1,
      maxQueued = conf.internalMaxQueuedRootGroupUpdates()
    )
  }

  @Provides
  @Singleton
  def provideGroupManager(
    @Named(ModuleNames.SERIALIZE_GROUP_UPDATES) serializeUpdates: CapConcurrentExecutions,
    scheduler: MarathonSchedulerService,
    groupRepo: GroupRepository,
    appRepo: ReadOnlyAppRepository,
    storage: StorageProvider,
    eventBus: EventStream,
    metrics: Metrics)(implicit mat: Materializer): GroupManager = {
    val groupManager: GroupManager = new GroupManager(
      serializeUpdates,
      scheduler,
      groupRepo,
      appRepo,
      storage,
      conf,
      eventBus
    )

    metrics.gauge("service.mesosphere.marathon.app.count", new Gauge[Int] {
      override def getValue: Int = {
        Await.result(groupManager.rootGroup(), conf.zkTimeoutDuration).transitiveApps.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.group.count", new Gauge[Int] {
      override def getValue: Int = {
        Await.result(groupManager.rootGroup(), conf.zkTimeoutDuration).transitiveGroups.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.uptime", new Gauge[Long] {
      val startedAt = System.currentTimeMillis()

      override def getValue: Long = {
        System.currentTimeMillis() - startedAt
      }
    })

    groupManager
  }

  @Provides
  @Singleton
  def provideEventBus(actorSystem: ActorSystem): EventStream = actorSystem.eventStream
}
