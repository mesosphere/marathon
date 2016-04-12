package mesosphere.marathon

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.event.EventStream
import akka.routing.RoundRobinPool
import com.codahale.metrics.Gauge
import com.google.inject._
import com.google.inject.name.Names
import com.twitter.common.base.Supplier
import com.twitter.common.zookeeper.{ Candidate, Group => ZGroup, ZooKeeperClient }
import com.twitter.util.JavaTimer
import com.twitter.zk.{ NativeConnector, ZkClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.launcher.TaskOpFactory
import mesosphere.marathon.core.launcher.impl.TaskOpFactoryImpl
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.CandidateImpl
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.http._
import mesosphere.marathon.event.{ EventModule, HistoryActor }
import mesosphere.marathon.health.{ HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan }
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.mesos.MesosStateStore
import mesosphere.util.state.zk.{ CompressionConf, ZKStore }
import mesosphere.util.state.{ FrameworkId, FrameworkIdUtil, PersistentStore, _ }
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooDefs.Ids
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object ModuleNames {
  final val CANDIDATE = "CANDIDATE"
  final val HOST_PORT = "HOST_PORT"

  final val LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val SERVER_SET_PATH = "SERVER_SET_PATH"
  final val SERIALIZE_GROUP_UPDATES = "SERIALIZE_GROUP_UPDATES"
  final val HTTP_EVENT_STREAM = "HTTP_EVENT_STREAM"

  final val STORE_APP = "AppStore"
  final val STORE_TASK_FAILURES = "TaskFailureStore"
  final val STORE_DEPLOYMENT_PLAN = "DeploymentPlanStore"
  final val STORE_FRAMEWORK_ID = "FrameworkIdStore"
  final val STORE_GROUP = "GroupStore"
  final val STORE_TASK = "TaskStore"
  final val STORE_EVENT_SUBSCRIBERS = "EventSubscriberStore"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, zk: ZooKeeperClient)
    extends AbstractModule {

  //scalastyle:off magic.number

  val log = LoggerFactory.getLogger(getClass.getName)

  def configure() {

    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[ZooKeeperClient]).toInstance(zk)
    bind(classOf[LeaderProxyConf]).toInstance(conf)
    bind(classOf[ZookeeperConf]).toInstance(conf)

    // needs to be eager to break circular dependencies
    bind(classOf[SchedulerCallbacks]).to(classOf[SchedulerCallbacksServiceAdapter]).asEagerSingleton()

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonLeaderInfoMetrics]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[LeadershipAbdication]).to(classOf[MarathonSchedulerService])
    bind(classOf[LeaderInfo]).to(classOf[MarathonLeaderInfo]).in(Scopes.SINGLETON)
    bind(classOf[TaskOpFactory]).to(classOf[TaskOpFactoryImpl]).in(Scopes.SINGLETON)

    bind(classOf[HealthCheckManager]).to(classOf[MarathonHealthCheckManager]).asEagerSingleton()

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)

    bind(classOf[Metrics]).in(Scopes.SINGLETON)
    bind(classOf[HttpEventStreamActorMetrics]).in(Scopes.SINGLETON)

    // If running in single scheduler mode, this node is the leader.
    val leader = new AtomicBoolean(!conf.highlyAvailable())
    bind(classOf[AtomicBoolean])
      .annotatedWith(Names.named(ModuleNames.LEADER_ATOMIC_BOOLEAN))
      .toInstance(leader)
  }

  @Provides
  @Singleton
  def provideMesosLeaderInfo(): MesosLeaderInfo = {
    conf.mesosLeaderUiUrl.get match {
      case someUrl @ Some(_) => ConstMesosLeaderInfo(someUrl)
      case None              => new MutableMesosLeaderInfo
    }
  }

  @Provides
  @Singleton
  def provideLeadershipCallbacks(
    @Named(ModuleNames.STORE_APP) app: EntityStore[AppDefinition],
    @Named(ModuleNames.STORE_GROUP) group: EntityStore[Group],
    @Named(ModuleNames.STORE_DEPLOYMENT_PLAN) deployment: EntityStore[DeploymentPlan],
    @Named(ModuleNames.STORE_FRAMEWORK_ID) frameworkId: EntityStore[FrameworkId],
    @Named(ModuleNames.STORE_TASK_FAILURES) taskFailure: EntityStore[TaskFailure],
    @Named(ModuleNames.STORE_EVENT_SUBSCRIBERS) subscribers: EntityStore[EventSubscribers],
    @Named(ModuleNames.STORE_TASK) task: EntityStore[MarathonTaskState]): Seq[LeadershipCallback] = {
    Seq(app, group, deployment, frameworkId, taskFailure, task, subscribers).collect { case l: LeadershipCallback => l }
  }

  @Named(ModuleNames.HTTP_EVENT_STREAM)
  @Provides
  @Singleton
  def provideHttpEventStreamActor(system: ActorSystem,
                                  leaderInfo: LeaderInfo,
                                  @Named(EventModule.busName) eventBus: EventStream,
                                  metrics: HttpEventStreamActorMetrics): ActorRef = {
    val outstanding = conf.eventStreamMaxOutstandingMessages.get.getOrElse(50)
    def handleStreamProps(handle: HttpEventStreamHandle): Props =
      Props(new HttpEventStreamHandleActor(handle, eventBus, outstanding))

    system.actorOf(Props(new HttpEventStreamActor(leaderInfo, metrics, handleStreamProps)), "HttpEventStream")
  }

  @Provides
  @Singleton
  def provideStore(): PersistentStore = {
    def directZK(): PersistentStore = {
      import com.twitter.util.TimeConversions._
      val sessionTimeout = conf.zooKeeperSessionTimeout().millis
      val connector = NativeConnector(conf.zkHosts, None, sessionTimeout, new JavaTimer(isDaemon = true))
      val client = ZkClient(connector)
        .withAcl(Ids.OPEN_ACL_UNSAFE.asScala)
        .withRetries(3)
      val compressionConf = CompressionConf(conf.zooKeeperCompressionEnabled(), conf.zooKeeperCompressionThreshold())
      new ZKStore(client, client(conf.zooKeeperStatePath), compressionConf)
    }
    def mesosZK(): PersistentStore = {
      val state = new ZooKeeperState(
        conf.zkHosts,
        conf.zkTimeoutDuration.toMillis,
        TimeUnit.MILLISECONDS,
        conf.zooKeeperStatePath
      )
      new MesosStateStore(state, conf.zkTimeoutDuration)
    }
    conf.internalStoreBackend.get match {
      case Some("zk")              => directZK()
      case Some("mesos_zk")        => mesosZK()
      case Some("mem")             => new InMemoryStore()
      case backend: Option[String] => throw new IllegalArgumentException(s"Storage backend $backend not known!")
    }
  }

  //scalastyle:off parameter.number method.length
  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  def provideSchedulerActor(
    system: ActorSystem,
    appRepository: AppRepository,
    groupRepository: GroupRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    launchQueue: LaunchQueue,
    frameworkIdUtil: FrameworkIdUtil,
    driverHolder: MarathonSchedulerDriverHolder,
    leaderInfo: LeaderInfo,
    storage: StorageProvider,
    @Named(EventModule.busName) eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    taskFailureRepository: TaskFailureRepository): ActorRef = {
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

    val historyActorProps = Props(new HistoryActor(eventBus, taskFailureRepository))

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
        leaderInfo,
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

  @Named(ModuleNames.CANDIDATE)
  @Provides
  @Singleton
  def provideCandidate(zk: ZooKeeperClient, @Named(ModuleNames.HOST_PORT) hostPort: String): Option[Candidate] = {
    if (conf.highlyAvailable()) {
      log.info("Registering in ZooKeeper with hostPort:" + hostPort)
      val candidate = new CandidateImpl(new ZGroup(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE, conf.zooKeeperLeaderPath),
        new Supplier[Array[Byte]] {
          def get(): Array[Byte] = {
            hostPort.getBytes("UTF-8")
          }
        })
      return Some(candidate) //scalastyle:off return
    }
    None
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
  def provideFrameworkIdUtil(
    @Named(ModuleNames.STORE_FRAMEWORK_ID) store: EntityStore[FrameworkId],
    metrics: Metrics): FrameworkIdUtil = {
    new FrameworkIdUtil(store, conf.zkTimeoutDuration)
  }

  @Provides
  @Singleton
  def provideMigration(
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    taskRepo: TaskRepository,
    metrics: Metrics): Migration = {
    new Migration(store, appRepo, groupRepo, taskRepo, conf, metrics)
  }

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
      maxParallel = 1,
      maxQueued = conf.internalMaxQueuedRootGroupUpdates()
    )
  }

  @Provides
  @Singleton
  def provideGroupManager(
    @Named(ModuleNames.SERIALIZE_GROUP_UPDATES) serializeUpdates: CapConcurrentExecutions,
    scheduler: MarathonSchedulerService,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    @Named(EventModule.busName) eventBus: EventStream,
    metrics: Metrics): GroupManager = {
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

  // persistence functionality ----------------

  @Provides
  @Singleton
  def provideTaskFailureRepository(
    @Named(ModuleNames.STORE_TASK_FAILURES) store: EntityStore[TaskFailure],
    metrics: Metrics): TaskFailureRepository = {
    new TaskFailureRepository(store, conf.zooKeeperMaxVersions.get, metrics)
  }

  @Provides
  @Singleton
  def provideAppRepository(
    @Named(ModuleNames.STORE_APP) store: EntityStore[AppDefinition],
    metrics: Metrics): AppRepository = {
    new AppRepository(store, maxVersions = conf.zooKeeperMaxVersions.get, metrics)
  }

  @Provides
  @Singleton
  def provideGroupRepository(
    @Named(ModuleNames.STORE_GROUP) store: EntityStore[Group],
    appRepository: AppRepository,
    metrics: Metrics): GroupRepository = {
    new GroupRepository(store, conf.zooKeeperMaxVersions.get, metrics)
  }

  @Provides
  @Singleton
  def provideTaskRepository(
    @Named(ModuleNames.STORE_TASK) store: EntityStore[MarathonTaskState],
    metrics: Metrics): TaskRepository = {
    new TaskRepository(store, metrics)
  }

  @Provides
  @Singleton
  def provideDeploymentRepository(
    @Named(ModuleNames.STORE_DEPLOYMENT_PLAN) store: EntityStore[DeploymentPlan],
    conf: MarathonConf,
    metrics: Metrics): DeploymentRepository = {
    new DeploymentRepository(store, conf.zooKeeperMaxVersions.get, metrics)
  }

  @Named(ModuleNames.STORE_DEPLOYMENT_PLAN)
  @Provides
  @Singleton
  def provideDeploymentPlanStore(store: PersistentStore, metrics: Metrics): EntityStore[DeploymentPlan] = {
    entityStore(store, metrics, "deployment:", () => DeploymentPlan.empty)
  }

  @Named(ModuleNames.STORE_FRAMEWORK_ID)
  @Provides
  @Singleton
  def provideFrameworkIdStore(store: PersistentStore, metrics: Metrics): EntityStore[FrameworkId] = {
    entityStore(store, metrics, "framework:", () => new FrameworkId(UUID.randomUUID().toString))
  }

  @Named(ModuleNames.STORE_GROUP)
  @Provides
  @Singleton
  def provideGroupStore(store: PersistentStore, metrics: Metrics): EntityStore[Group] = {
    entityStore(store, metrics, "group:", () => Group.empty)
  }

  @Named(ModuleNames.STORE_APP)
  @Provides
  @Singleton
  def provideAppStore(store: PersistentStore, metrics: Metrics): EntityStore[AppDefinition] = {
    entityStore(store, metrics, "app:", () => AppDefinition.apply())
  }

  @Named(ModuleNames.STORE_TASK_FAILURES)
  @Provides
  @Singleton
  def provideTaskFailreStore(store: PersistentStore, metrics: Metrics): EntityStore[TaskFailure] = {
    import org.apache.mesos.{ Protos => mesos }
    entityStore(store, metrics, "taskFailure:",
      () => TaskFailure(
        PathId.empty,
        mesos.TaskID.newBuilder().setValue("").build,
        mesos.TaskState.TASK_STAGING
      )
    )
  }

  @Named(ModuleNames.STORE_TASK)
  @Provides
  @Singleton
  def provideTaskStore(store: PersistentStore, metrics: Metrics): EntityStore[MarathonTaskState] = {
    // intentionally uncached since we cache in the layer above
    new MarathonStore[MarathonTaskState](
      store,
      metrics,
      prefix = "task:",
      newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build())
    )
  }

  @Named(ModuleNames.STORE_EVENT_SUBSCRIBERS)
  @Provides
  @Singleton
  def provideEventSubscribersStore(store: PersistentStore, metrics: Metrics): EntityStore[EventSubscribers] = {
    entityStore(store, metrics, "events:", () => new EventSubscribers(Set.empty[String]))
  }

  private[this] def entityStore[T <: mesosphere.marathon.state.MarathonState[_, T]](
    store: PersistentStore,
    metrics: Metrics,
    prefix: String,
    newState: () => T)(implicit ct: ClassTag[T]): EntityStore[T] = {
    val marathonStore = new MarathonStore[T](store, metrics, newState, prefix)
    if (conf.storeCache()) new EntityStoreCache[T](marathonStore) else marathonStore
  }
}
