package mesosphere.marathon

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.util.control.NonFatal

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ ActorRef, ActorSystem, OneForOneStrategy, Props }
import akka.event.EventStream
import akka.routing.RoundRobinPool
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject._
import com.google.inject.name.Names
import com.twitter.common.base.Supplier
import com.twitter.common.zookeeper.{ Candidate, CandidateImpl, ZooKeeperClient, Group => ZGroup }
import org.apache.log4j.Logger
import org.apache.mesos.state.{ State, ZooKeeperState }
import org.apache.zookeeper.ZooDefs

import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.health.{ DelegatingHealthCheckManager, HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.mesos.util.FrameworkIdUtil

object ModuleNames {
  final val NAMED_CANDIDATE = "CANDIDATE"
  final val NAMED_LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val NAMED_SERVER_SET_PATH = "SERVER_SET_PATH"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, zk: ZooKeeperClient)
    extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {
    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[ZooKeeperClient]).toInstance(zk)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[TaskTracker]).in(Scopes.SINGLETON)
    bind(classOf[TaskQueue]).in(Scopes.SINGLETON)

    bind(classOf[GroupManager]).in(Scopes.SINGLETON)

    bind(classOf[HealthCheckManager]).to(
      conf.executorHealthChecks() match {
        case false => classOf[MarathonHealthCheckManager]
        case true  => classOf[DelegatingHealthCheckManager]
      }
    ).asEagerSingleton()

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.NAMED_SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)

    // If running in single scheduler mode, this node is the leader.
    val leader = new AtomicBoolean(!conf.highlyAvailable())
    bind(classOf[AtomicBoolean])
      .annotatedWith(Names.named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN))
      .toInstance(leader)

  }

  @Provides
  @Singleton
  def provideMesosState(): State = {
    new ZooKeeperState(
      conf.zkHosts,
      conf.zkTimeoutDuration.toMillis,
      TimeUnit.MILLISECONDS,
      conf.zooKeeperStatePath
    )
  }

  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  def provideSchedulerActor(
    @Named("restMapper") mapper: ObjectMapper,
    system: ActorSystem,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    taskIdUtil: TaskIdUtil,
    storage: StorageProvider,
    @Named(EventModule.busName) eventBus: EventStream,
    config: MarathonConf): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    system.actorOf(
      Props(
        classOf[MarathonSchedulerActor],
        mapper,
        appRepository,
        deploymentRepository,
        healthCheckManager,
        taskTracker,
        taskQueue,
        frameworkIdUtil,
        taskIdUtil,
        storage,
        eventBus,
        config).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.NAMED_CANDIDATE)
  @Provides
  @Singleton
  def provideCandidate(zk: ZooKeeperClient): Option[Candidate] = {
    if (Main.conf.highlyAvailable()) {
      log.info("Registering in Zookeeper with hostname:"
        + Main.conf.hostname())
      val candidate = new CandidateImpl(new ZGroup(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE,
        Main.conf.zooKeeperLeaderPath),
        new Supplier[Array[Byte]] {
          def get() = {
            //host:port
            "%s:%d".format(Main.conf.hostname(),
              Main.conf.httpPort()).getBytes
          }
        })
      return Some(candidate)
    }
    None
  }

  @Provides
  @Singleton
  def provideAppRepository(state: State, conf: MarathonConf): AppRepository = new AppRepository(
    new MarathonStore[AppDefinition](state, () => AppDefinition.apply()), conf.zooKeeperMaxVersions.get
  )

  @Provides
  @Singleton
  def provideGroupRepository(state: State, appRepository: AppRepository, conf: MarathonConf): GroupRepository = new GroupRepository(
    new MarathonStore[Group](state, () => Group.empty, "group:"), appRepository, conf.zooKeeperMaxVersions.get
  )

  @Provides
  @Singleton
  def provideDeploymentRepository(state: State, conf: MarathonConf) = new DeploymentRepository(
    new MarathonStore[DeploymentPlan](state, () => DeploymentPlan.empty, "deployment:"),
    conf.zooKeeperMaxVersions.get
  )

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = ActorSystem("marathon")

  @Provides
  @Singleton
  def provideFrameworkIdUtil(state: State): FrameworkIdUtil =
    new FrameworkIdUtil(state)

  @Provides
  @Singleton
  def provideMigration(state: State, appRepo: AppRepository, groupRepo: GroupRepository, config: MarathonConf): Migration =
    new Migration(state, appRepo, groupRepo, config)

  @Provides
  @Singleton
  def provideTaskIdUtil(): TaskIdUtil = new TaskIdUtil

  @Provides
  @Singleton
  def provideStorageProvider(config: MarathonConf, http: HttpConf): StorageProvider = StorageProvider.provider(config, http)
}
