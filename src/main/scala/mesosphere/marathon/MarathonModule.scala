package mesosphere.marathon

import com.google.inject._
import org.apache.mesos.state.{ZooKeeperState, State}
import java.util.concurrent.TimeUnit
import com.twitter.common.zookeeper.{Group, CandidateImpl, Candidate, ZooKeeperClient}
import org.apache.zookeeper.ZooDefs
import com.twitter.common.base.Supplier
import org.apache.log4j.Logger
import javax.inject.Named
import java.util.concurrent.atomic.AtomicBoolean
import com.google.inject.name.Names
import akka.actor.ActorSystem
import mesosphere.marathon.state.{MarathonStore, AppRepository}
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters

/**
 * @author Tobi Knaup
 */
object ModuleNames {
  final val NAMED_CANDIDATE = "CANDIDATE"
  final val NAMED_LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val NAMED_SERVER_SET_PATH = "SERVER_SET_PATH"
}

class MarathonModule(conf: MarathonConf with ZookeeperConf, zk: ZooKeeperClient)
  extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {
    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[ZooKeeperClient]).toInstance(zk)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[TaskTracker]).in(Scopes.SINGLETON)
    bind(classOf[HealthCheckManager]).in(Scopes.SINGLETON)

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.NAMED_SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)

    //If running in single scheduler mode, this node is the leader.
    val leader = new AtomicBoolean(!conf.highlyAvailable())
    bind(classOf[AtomicBoolean])
      .annotatedWith(Names.named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN))
      .toInstance(leader)

    val rateLimiters = new RateLimiters()
    bind(classOf[RateLimiters]).toInstance(rateLimiters)
  }

  @Provides
  @Singleton
  def provideMesosState(): State = {
    new ZooKeeperState(
      conf.zkHosts,
      conf.zooKeeperTimeout.get.get,
      TimeUnit.MILLISECONDS,
      conf.zooKeeperStatePath
    )
  }

  @Named(ModuleNames.NAMED_CANDIDATE)
  @Provides
  @Singleton
  def provideCandidate(zk: ZooKeeperClient): Option[Candidate] = {
    if (Main.conf.highlyAvailable()) {
      log.info("Registering in Zookeeper with hostname:"
        + Main.conf.hostname())
      val candidate = new CandidateImpl(new Group(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE,
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
  def provideAppRepository(state: State): AppRepository = new AppRepository(
    new MarathonStore[AppDefinition](state, () => AppDefinition.apply())
  )

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = ActorSystem("marathon")

  @Provides
  @Singleton
  def provideFrameworkIdUtil(state: State): FrameworkIdUtil =
    new FrameworkIdUtil(state)

}
