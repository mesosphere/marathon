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
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject._
import com.google.inject.name.Names
import com.twitter.common.base.Supplier
import com.twitter.common.zookeeper.{ Candidate, CandidateImpl, Group => ZGroup, ZooKeeperClient }
import com.twitter.zk.{ NativeConnector, ZkClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.event.http._
import mesosphere.marathon.event.{ EventModule, HistoryActor }
import mesosphere.marathon.health.{ HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker, _ }
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan }
import mesosphere.util.SerializeExecution
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.mesos.MesosStateStore
import mesosphere.util.state.zk.ZKStore
import mesosphere.util.state.{ FrameworkId, FrameworkIdUtil, PersistentStore }
import org.apache.log4j.Logger
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooDefs.Ids

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.collection.immutable.Seq

object ModuleNames {
  final val NAMED_CANDIDATE = "CANDIDATE"
  final val NAMED_HOST_PORT = "HOST_PORT"

  final val NAMED_LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val NAMED_SERVER_SET_PATH = "SERVER_SET_PATH"
  final val NAMED_SERIALIZE_GROUP_UPDATES = "SERIALIZE_GROUP_UPDATES"
  final val NAMED_HTTP_EVENT_STREAM = "HTTP_EVENT_STREAM"

  final val NAMED_STORE_APP = "AppStore"
  final val NAMED_STORE_TASK_FAILURES = "TaskFailureStore"
  final val NAMED_STORE_DEPLOYMENT_PLAN = "DeploymentPlanStore"
  final val NAMED_STORE_FRAMEWORK_ID = "FrameworkIdStore"
  final val NAMED_STORE_GROUP = "GroupStore"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, zk: ZooKeeperClient)
    extends AbstractModule {

  //scalastyle:off magic.number

  val log = Logger.getLogger(getClass.getName)

  def configure() {

    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[ZooKeeperClient]).toInstance(zk)
    bind(classOf[LeaderProxyConf]).toInstance(conf)
    bind(classOf[OfferReviverConf]).toInstance(conf)
    bind(classOf[IterativeOfferMatcherConfig]).toInstance(conf)

    // needs to be eager to break circular dependencies
    bind(classOf[SchedulerCallbacks]).to(classOf[SchedulerCallbacksServiceAdapter]).asEagerSingleton()

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonLeaderInfoMetrics]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[LeaderInfo]).to(classOf[MarathonLeaderInfo]).in(Scopes.SINGLETON)
    bind(classOf[TaskTracker]).in(Scopes.SINGLETON)
    bind(classOf[TaskQueue]).in(Scopes.SINGLETON)
    bind(classOf[TaskFactory]).to(classOf[DefaultTaskFactory]).in(Scopes.SINGLETON)
    bind(classOf[IterativeOfferMatcherMetrics]).in(Scopes.SINGLETON)
    bind(classOf[OfferMatcher]).to(classOf[IterativeOfferMatcher]).in(Scopes.SINGLETON)

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
  def provideLeadershipCallbacks(
    @Named(ModuleNames.NAMED_STORE_APP) app: EntityStore[AppDefinition],
    @Named(ModuleNames.NAMED_STORE_GROUP) group: EntityStore[Group],
    @Named(ModuleNames.NAMED_STORE_DEPLOYMENT_PLAN) deployment: EntityStore[DeploymentPlan],
    @Named(ModuleNames.NAMED_STORE_FRAMEWORK_ID) frameworkId: EntityStore[FrameworkId],
    @Named(ModuleNames.NAMED_STORE_TASK_FAILURES) taskFailure: EntityStore[TaskFailure]): Seq[LeadershipCallback] = {
    Seq(app, group, deployment, frameworkId, taskFailure).collect { case l: LeadershipCallback => l }
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
    @Named("restMapper") mapper: ObjectMapper,
    system: ActorSystem,
    appRepository: AppRepository,
    groupRepository: GroupRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
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

  @Named(OfferReviverActor.NAME)
  @Provides
  @Singleton
  @Inject
  def provideOfferReviverActor(
    system: ActorSystem,
    conf: OfferReviverConf,
    @Named(EventModule.busName) eventBus: EventStream,
    driverHolder: MarathonSchedulerDriverHolder): ActorRef =
    {
      val props = OfferReviverActor.props(conf, eventBus, driverHolder)
      system.actorOf(props, OfferReviverActor.NAME)
    }

  @Provides
  @Singleton
  @Inject
  def provideOfferReviver(@Named(OfferReviverActor.NAME) reviverRef: ActorRef): OfferReviver = {
    new OfferReviverDelegate(reviverRef)
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
    @Named(ModuleNames.NAMED_STORE_TASK_FAILURES) store: EntityStore[TaskFailure]): TaskFailureRepository = {
    new TaskFailureRepository(store, conf.zooKeeperMaxVersions.get)
  }

  @Provides
  @Singleton
  def provideAppRepository(
    @Named(ModuleNames.NAMED_STORE_APP) store: EntityStore[AppDefinition],
    metrics: Metrics): AppRepository = {
    new AppRepository(
      store,
      maxVersions = conf.zooKeeperMaxVersions.get,
      metrics
    )
  }

  @Provides
  @Singleton
  def provideGroupRepository(
    @Named(ModuleNames.NAMED_STORE_GROUP) store: EntityStore[Group],
    appRepository: AppRepository,
    metrics: Metrics): GroupRepository = {
    new GroupRepository(
      store,
      appRepository, conf.zooKeeperMaxVersions.get,
      metrics
    )
  }

  @Provides
  @Singleton
  def provideDeploymentRepository(
    @Named(ModuleNames.NAMED_STORE_DEPLOYMENT_PLAN) store: EntityStore[DeploymentPlan],
    conf: MarathonConf,
    metrics: Metrics): DeploymentRepository = {
    new DeploymentRepository(
      store,
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
  def provideFrameworkIdUtil(
    @Named(ModuleNames.NAMED_STORE_FRAMEWORK_ID) store: EntityStore[FrameworkId],
    metrics: Metrics): FrameworkIdUtil = {
    new FrameworkIdUtil(store, conf.zkTimeoutDuration)
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
    storage: StorageProvider,
    config: MarathonConf,
    @Named(EventModule.busName) eventBus: EventStream,
    metrics: Metrics): GroupManager = {
    val groupManager: GroupManager = new GroupManager(
      serializeUpdates,
      scheduler,
      taskTracker,
      groupRepo,
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

  @Named(ModuleNames.NAMED_STORE_DEPLOYMENT_PLAN)
  @Provides
  @Singleton
  def provideDeploymentPlanStore(store: PersistentStore, metrics: Metrics): EntityStore[DeploymentPlan] = {
    entityStore(conf, store, metrics, "deployment:", () => DeploymentPlan.empty)
  }

  @Named(ModuleNames.NAMED_STORE_FRAMEWORK_ID)
  @Provides
  @Singleton
  def provideFrameworkIdStore(store: PersistentStore, metrics: Metrics): EntityStore[FrameworkId] = {
    entityStore(conf, store, metrics, "", () => new FrameworkId(UUID.randomUUID().toString))
  }

  @Named(ModuleNames.NAMED_STORE_GROUP)
  @Provides
  @Singleton
  def provideGroupStore(store: PersistentStore, metrics: Metrics): EntityStore[Group] = {
    entityStore(conf, store, metrics, "group:", () => Group.empty)
  }

  @Named(ModuleNames.NAMED_STORE_APP)
  @Provides
  @Singleton
  def provideAppStore(store: PersistentStore, metrics: Metrics): EntityStore[AppDefinition] = {
    entityStore(conf, store, metrics, "app:", () => AppDefinition.apply())
  }

  @Named(ModuleNames.NAMED_STORE_TASK_FAILURES)
  @Provides
  @Singleton
  def provideTaskFailreStore(store: PersistentStore, metrics: Metrics): EntityStore[TaskFailure] = {
    import org.apache.mesos.{ Protos => mesos }
    entityStore(conf, store, metrics, "taskFailure:",
      () => TaskFailure(
        PathId.empty,
        mesos.TaskID.newBuilder().setValue("").build,
        mesos.TaskState.TASK_STAGING
      )
    )
  }

  private[this] def entityStore[T <: mesosphere.marathon.state.MarathonState[_, T]](
    conf: MarathonConf,
    store: PersistentStore,
    metrics: Metrics,
    prefix: String,
    newState: () => T)(implicit ct: ClassTag[T]): EntityStore[T] = {
    val marathonStore = new MarathonStore[T](store, metrics, newState, prefix)
    new EntityStoreCache[T](marathonStore)
  }
}
