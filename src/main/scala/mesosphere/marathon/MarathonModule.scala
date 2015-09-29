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
import com.twitter.common.zookeeper.{ Candidate, CandidateImpl, Group => ZGroup, ZooKeeperClient }
import com.twitter.zk.{ NativeConnector, ZkClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.event.http._
import mesosphere.marathon.event.{ EventModule, HistoryActor }
import mesosphere.marathon.health.{ HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker, _ }
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan }
import mesosphere.util.SerializeExecution
import mesosphere.util.state._
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.mesos.MesosStateStore
import mesosphere.util.state.zk.ZKStore
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooDefs.Ids
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.util.control.NonFatal

object ModuleNames {
  final val NAMED_CANDIDATE = "CANDIDATE"
  final val NAMED_HOST_PORT = "HOST_PORT"

  final val NAMED_LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val NAMED_SERVER_SET_PATH = "SERVER_SET_PATH"
  final val NAMED_SERIALIZE_GROUP_UPDATES = "SERIALIZE_GROUP_UPDATES"
  final val NAMED_HTTP_EVENT_STREAM = "HTTP_EVENT_STREAM"
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

    // needs to be eager to break circular dependencies
    bind(classOf[SchedulerCallbacks]).to(classOf[SchedulerCallbacksServiceAdapter]).asEagerSingleton()

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonLeaderInfoMetrics]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[LeaderInfo]).to(classOf[MarathonLeaderInfo]).in(Scopes.SINGLETON)
    bind(classOf[TaskTracker]).in(Scopes.SINGLETON)
    bind(classOf[TaskFactory]).to(classOf[DefaultTaskFactory]).in(Scopes.SINGLETON)

    bind(classOf[HealthCheckManager]).to(classOf[MarathonHealthCheckManager]).asEagerSingleton()

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.NAMED_SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)

    bind(classOf[Metrics]).in(Scopes.SINGLETON)
    bind(classOf[HttpEventStreamActorMetrics]).in(Scopes.SINGLETON)

    // If running in single scheduler mode, this node is the leader.
    val leader = new AtomicBoolean(!conf.highlyAvailable())
    bind(classOf[AtomicBoolean])
      .annotatedWith(Names.named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN))
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

  @Named(ModuleNames.NAMED_HTTP_EVENT_STREAM)
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
      implicit val timer = com.twitter.util.Timer.Nil
      import com.twitter.util.TimeConversions._
      val sessionTimeout = conf.zooKeeperSessionTimeout.get.map(_.millis).getOrElse(30.minutes)
      val connector = NativeConnector(conf.zkHosts, None, sessionTimeout, timer)
      val client = ZkClient(connector)
        .withAcl(Ids.OPEN_ACL_UNSAFE.asScala)
        .withRetries(3)
      new ZKStore(client, client(conf.zooKeeperStatePath))
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
    taskQueue: LaunchQueue,
    frameworkIdUtil: FrameworkIdUtil,
    driverHolder: MarathonSchedulerDriverHolder,
    taskIdUtil: TaskIdUtil,
    leaderInfo: LeaderInfo,
    storage: StorageProvider,
    @Named(EventModule.busName) eventBus: EventStream,
    taskFailureRepository: TaskFailureRepository,
    config: MarathonConf): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    import system.dispatcher

    def createSchedulerActions(schedulerActor: ActorRef): SchedulerActions = {
      new SchedulerActions(
        appRepository,
        groupRepository,
        healthCheckManager,
        taskTracker,
        taskQueue,
        eventBus,
        schedulerActor,
        config)
    }

    def deploymentManagerProps(schedulerActions: SchedulerActions): Props = {
      Props(
        new DeploymentManager(
          appRepository,
          taskTracker,
          taskQueue,
          schedulerActions,
          storage,
          healthCheckManager,
          eventBus
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
        taskQueue,
        driverHolder,
        leaderInfo,
        eventBus
      ).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.NAMED_HOST_PORT)
  @Provides
  @Singleton
  def provideHostPort: String = {
    val port = if (http.disableHttp()) http.httpsPort() else http.httpPort()
    "%s:%d".format(conf.hostname(), port)
  }

  @Named(ModuleNames.NAMED_CANDIDATE)
  @Provides
  @Singleton
  def provideCandidate(zk: ZooKeeperClient, @Named(ModuleNames.NAMED_HOST_PORT) hostPort: String): Option[Candidate] = {
    if (conf.highlyAvailable()) {
      log.info("Registering in Zookeeper with hostPort:" + hostPort)
      val candidate = new CandidateImpl(new ZGroup(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE, conf.zooKeeperLeaderPath),
        new Supplier[Array[Byte]] {
          def get(): Array[Byte] = {
            hostPort.getBytes("UTF-8")
          }
        })
      //scalastyle:off return
      return Some(candidate)
      //scalastyle:on
    }
    None
  }

  @Provides
  @Singleton
  def provideTaskFailureRepository(
    store: PersistentStore,
    conf: MarathonConf,
    metrics: Metrics): TaskFailureRepository = {
    new TaskFailureRepository(
      new MarathonStore[TaskFailure](
        store,
        metrics,
        () => TaskFailure.empty,
        prefix = "taskFailure:"
      ),
      conf.zooKeeperMaxVersions.get,
      metrics
    )
  }

  @Provides
  @Singleton
  def provideAppRepository(
    store: PersistentStore,
    conf: MarathonConf,
    metrics: Metrics): AppRepository = {
    new AppRepository(
      new MarathonStore[AppDefinition](store, metrics, () => AppDefinition.apply(), prefix = "app:"),
      maxVersions = conf.zooKeeperMaxVersions.get,
      metrics
    )
  }

  @Provides
  @Singleton
  def provideGroupRepository(
    store: PersistentStore,
    conf: MarathonConf,
    metrics: Metrics): GroupRepository = {
    new GroupRepository(
      new MarathonStore[Group](store, metrics, () => Group.empty, "group:"),
      conf.zooKeeperMaxVersions.get,
      metrics
    )
  }

  @Provides
  @Singleton
  def provideDeploymentRepository(
    store: PersistentStore,
    conf: MarathonConf,
    metrics: Metrics): DeploymentRepository = {
    new DeploymentRepository(
      new MarathonStore[DeploymentPlan](store, metrics, () => DeploymentPlan.empty, "deployment:"),
      conf.zooKeeperMaxVersions.get,
      metrics
    )
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
  def provideFrameworkIdUtil(store: PersistentStore, metrics: Metrics, conf: MarathonConf): FrameworkIdUtil = {
    new FrameworkIdUtil(
      new MarathonStore[FrameworkId](store, metrics, () => new FrameworkId(UUID.randomUUID().toString), ""),
      conf.zkTimeoutDuration)
  }

  @Provides
  @Singleton
  def provideMigration(
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    metrics: Metrics,
    config: MarathonConf): Migration = {
    new Migration(store, appRepo, groupRepo, config, metrics)
  }

  @Provides
  @Singleton
  def provideTaskIdUtil(): TaskIdUtil = new TaskIdUtil

  @Provides
  @Singleton
  def provideStorageProvider(config: MarathonConf, http: HttpConf): StorageProvider =
    StorageProvider.provider(config, http)

  @Named(ModuleNames.NAMED_SERIALIZE_GROUP_UPDATES)
  @Provides
  @Singleton
  def provideSerializeGroupUpdates(actorRefFactory: ActorRefFactory): SerializeExecution = {
    SerializeExecution(actorRefFactory, "serializeGroupUpdates")
  }

  @Provides
  @Singleton
  def provideGroupManager(
    @Named(ModuleNames.NAMED_SERIALIZE_GROUP_UPDATES) serializeUpdates: SerializeExecution,
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    config: MarathonConf,
    @Named(EventModule.busName) eventBus: EventStream,
    metrics: Metrics): GroupManager = {
    val groupManager: GroupManager = new GroupManager(
      serializeUpdates,
      scheduler,
      taskTracker,
      groupRepo,
      appRepo,
      storage,
      config,
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

    groupManager
  }
}
