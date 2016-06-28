package mesosphere.marathon.core.election.impl

import java.net.InetSocketAddress
import java.util

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import com.twitter.common.base.{ ExceptionalCommand, Supplier }
import com.twitter.common.quantity.{ Time, Amount }
import com.twitter.common.zookeeper.Candidate.{ Leader => TwitterCommonsLeader }
import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.{ Candidate, Group, ZooKeeperClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.metrics.Metrics
import org.apache.zookeeper._
import org.apache.zookeeper.data.ACL
import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.collection.JavaConverters._

class TwitterCommonsElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  http: HttpConf,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  backoff: Backoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  config, system, eventStream, metrics, backoff, shutdownHooks
) {
  private lazy val log = LoggerFactory.getLogger(getClass.getName)
  private lazy val commonsCandidate = provideCandidate(zk)
  private lazy val abdicateOnConnectionLoss: AbdicateOnConnectionLoss = new AbdicateOnConnectionLoss(zk, this)

  override def leaderHostPortImpl: Option[String] = synchronized {
    val maybeLeaderData: Option[Array[Byte]] = try {
      Option(commonsCandidate.getLeaderData.orNull())
    } catch {
      case NonFatal(e) =>
        log.error("error while getting current leader", e)
        None
    }
    maybeLeaderData.map { data =>
      new String(data, "UTF-8")
    }
  }

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Using HA and therefore offering leadership")
    commonsCandidate.offerLeadership(Leader)
  }

  private object Leader extends TwitterCommonsLeader {
    override def onDefeated(): Unit = synchronized {
      log.info("Defeated (Leader Interface)")
      abdicateOnConnectionLoss.stop()
      stopLeadership()
    }

    override def onElected(abdicateCmd: ExceptionalCommand[JoinException]): Unit = synchronized {
      log.info("Elected (Leader Interface)")
      startLeadership(error => synchronized {
        abdicateCmd.execute()
        // stopLeadership() is called in onDefeated
      })
      abdicateOnConnectionLoss.start()
    }
  }

  private def provideCandidate(zk: ZooKeeperClient): Candidate = {
    log.info("Registering in ZooKeeper with hostPort:" + hostPort)

    // let the world read the leadership information as some setups depend on that to find Marathon
    lazy val acl = new util.ArrayList[ACL]()
    acl.addAll(config.zkDefaultCreationACL)
    acl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    new mesosphere.marathon.core.election.impl.CandidateImpl(
      new Group(zk, acl, config.zooKeeperLeaderPath),
      new Supplier[Array[Byte]] {
        def get(): Array[Byte] = {
          hostPort.getBytes("UTF-8")
        }
      }
    )
  }

  lazy val zk: ZooKeeperClient = {
    require(
      config.zooKeeperSessionTimeout() < Integer.MAX_VALUE,
      "ZooKeeper timeout too large!"
    )

    val sessionTimeout = Amount.of(config.zooKeeperSessionTimeout().toInt, Time.MILLISECONDS)
    val zooKeeperServers = config.zooKeeperHostAddresses.asJavaCollection
    val client = (config.zkUsername, config.zkPassword) match {
      case (Some(user), Some(pass)) =>
        new ZooKeeperClient(sessionTimeout, ZooKeeperClient.digestCredentials(user, pass), zooKeeperServers)
      case _ =>
        new ZooKeeperClient(sessionTimeout, zooKeeperServers)
    }

    // Marathon can't do anything useful without a ZK connection
    // so we wait to proceed until one is available
    var connectedToZk = false

    while (!connectedToZk) {
      try {
        log.info("Connecting to ZooKeeper...")
        client.get
        connectedToZk = true
      } catch {
        case t: Throwable =>
          log.warn("Unable to connect to ZooKeeper, retrying...")
      }
    }
    client
  }

  class ZooKeeperLeaderElectionClient(
    sessionTimeout: Amount[Integer, Time],
    zooKeeperServers: java.lang.Iterable[InetSocketAddress])
      extends ZooKeeperClient(sessionTimeout, zooKeeperServers) {

    override def shouldRetry(e: KeeperException): Boolean = {
      log.error("Got ZooKeeper exception", e)
      log.error("Committing suicide to avoid invalidating ZooKeeper state")

      val f = Future {
        // scalastyle:off magic.number
        Runtime.getRuntime.exit(9)
        // scalastyle:on
      }(scala.concurrent.ExecutionContext.global)

      try {
        Await.result(f, 5.seconds)
      } catch {
        case _: Throwable =>
          log.error("Finalization failed, killing JVM.")
          // scalastyle:off magic.number
          Runtime.getRuntime.halt(1)
        // scalastyle:on
      }

      false
    }
  }
}

class AbdicateOnConnectionLoss(
    zk: ZooKeeperClient,
    electionService: ElectionService) {
  private lazy val log = LoggerFactory.getLogger(getClass.getName)

  private val connectionDropped = Set(
    Watcher.Event.KeeperState.Disconnected,
    Watcher.Event.KeeperState.Expired
  )

  private[impl] val watcher = new Watcher {
    override def process(event: WatchedEvent): Unit = {
      if (connectionDropped.contains(event.getState)) {
        log.info(s"ZooKeeper connection has been dropped. Abdicate Leadership: $event")
        electionService.abdicateLeadership()
      }
    }
  }

  def start(): Unit = {
    log.info("Register as ZK Listener")
    zk.register(watcher)
    //make sure, we are connected so we can act on subsequent events
    if (zk.get().getState != ZooKeeper.States.CONNECTED) electionService.abdicateLeadership()
  }

  def stop(): Unit = {
    log.info("Unregister as ZK Listener")
    zk.unregister(watcher)
  }
}
