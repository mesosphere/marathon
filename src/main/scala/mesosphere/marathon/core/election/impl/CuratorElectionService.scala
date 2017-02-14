package mesosphere.marathon
package core.election.impl

import com.typesafe.scalalogging.StrictLogging
import java.util
import java.util.Collections
import java.util.concurrent.{ Executors, TimeUnit }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base._
import mesosphere.marathon.metrics.Metrics
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.framework.state.{ ConnectionState, ConnectionStateListener }
import org.apache.curator.framework.{ AuthInfo, CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.{ RetryPolicy, RetrySleeper }
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext

/**
  * Handles our election leader election concerns.
  *
  * TODO - This code should be substantially simplified https://github.com/mesosphere/marathon/issues/4908
  */
class CuratorElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  backoff: ExponentialBackoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  system, eventStream, metrics, backoff, shutdownHooks
) with StrictLogging {
  private val callbackExecutor = Executors.newSingleThreadExecutor()
  /* We re-use the single thread executor here because code locks (via synchronized) frequently */
  override protected implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(callbackExecutor)

  private lazy val client = provideCuratorClient()
  private var maybeLatch: Option[LeaderLatch] = None

  system.registerOnTermination {
    synchronized {
      logger.info("Stopping leadership on shutdown.")
      stopLeadership()
      client.close()
    }
  }

  override def leaderHostPortImpl: Option[String] = synchronized {
    if (client.getState() == CuratorFrameworkState.STOPPED) None
    else {
      try {
        maybeLatch.flatMap { latch =>
          val participant = latch.getLeader
          if (participant.isLeader) Some(participant.getId) else None
        }
      } catch {
        case NonFatal(e) =>
          logger.error("Error while getting current leader", e)
          None
      }
    }
  }

  override def offerLeadershipImpl(): Unit = synchronized {
    logger.info("Using HA and therefore offering leadership")
    maybeLatch.foreach { latch =>
      logger.info("Offering leadership while being candidate")
      if (client.getState() != CuratorFrameworkState.STOPPED) latch.close()
    }

    try {
      val latch = new LeaderLatch(client, config.zooKeeperLeaderPath + "-curator", hostPort, LeaderLatch.CloseMode.NOTIFY_LEADER)
      latch.addListener(LeaderChangeListener, callbackExecutor)
      latch.start()
      maybeLatch = Some(latch)
    } catch {
      case NonFatal(e) =>
        logger.error(s"ZooKeeper initialization failed - Committing suicide: ${e.getMessage}")
        Runtime.getRuntime.asyncExit()(scala.concurrent.ExecutionContext.global)
    }
  }

  /**
    * Listener which forwards leadership status events asynchronously via the provided function.
    *
    * We delegate the methods asynchronously so they are processed outside of the synchronized lock for LeaderLatch.setLeadership
    */
  private object LeaderChangeListener extends LeaderLatchListener {
    override def notLeader(): Unit = Future {
      logger.info(s"Leader defeated. New leader: ${leaderHostPort.getOrElse("-")}")
      stopLeadership()
    }

    override def isLeader(): Unit = Future {
      logger.info("Leader elected")
      startLeadership(onAbdicate(_))
    }
  }

  /**
    * Listens to connection changes and stops leadership when the connection to ZooKeeper is lost.
    *
    * The is the suggested behaviour in the [[http://curator.apache.org/curator-recipes/leader-latch.html LeaderLatch documentation]].
    *
    */
  private object ConnectionLostListener extends ConnectionStateListener {
    override def stateChanged(client: CuratorFramework, newState: ConnectionState): Unit = {
      if (!newState.isConnected) {
        onConnectionLoss()
      }
    }
  }

  private[this] def onConnectionLoss() = synchronized {
    logger.info("Lost connection to ZooKeeper as leader — Committing suicide")
    stopLeadership()
    client.close()
    Runtime.getRuntime.asyncExit()(scala.concurrent.ExecutionContext.global)
  }

  private[this] def onAbdicate(error: Boolean): Unit = synchronized {
    maybeLatch match {
      case None => logger.error(s"Abdicating leadership while not being leader (error: $error)")
      case Some(latch) =>
        maybeLatch = None
        try {
          if (client.getState() != CuratorFrameworkState.STOPPED) latch.close()
        } catch {
          case NonFatal(e) => logger.error("Could not close leader latch", e)
        }
    }
    // stopLeadership() is called by the leader Listener in notLeader
  }

  def provideCuratorClient(): CuratorFramework = {
    logger.info(s"Will do leader election through ${config.zkHosts}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val acl = new util.ArrayList[ACL]()
    acl.addAll(config.zkDefaultCreationACL)
    acl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val builder = CuratorFrameworkFactory.builder().
      connectString(config.zkHosts).
      sessionTimeoutMs(config.zooKeeperSessionTimeout().toInt).
      connectionTimeoutMs(config.zooKeeperTimeout().toInt).
      aclProvider(new ACLProvider {
        val rootAcl = {
          val acls = new util.ArrayList[ACL]()
          acls.addAll(acls)
          acls.addAll(ZooDefs.Ids.OPEN_ACL_UNSAFE)
          acls
        }

        override def getDefaultAcl: util.List[ACL] = acl

        override def getAclForPath(path: String): util.List[ACL] = if (path != config.zkPath) {
          acl
        } else {
          rootAcl
        }
      }).
      retryPolicy(new RetryPolicy {
        override def allowRetry(retryCount: Int, elapsedTimeMs: Long, sleeper: RetrySleeper): Boolean = {
          logger.error("ZooKeeper access failed — Committing suicide to avoid invalidating ZooKeeper state")
          Runtime.getRuntime.asyncExit()(scala.concurrent.ExecutionContext.global)
          false
        }
      })

    // optionally authenticate
    val client = (config.zkUsername, config.zkPassword) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Collections.singletonList(
          new AuthInfo("digest", (user + ":" + pass).getBytes("UTF-8"))
        )).build()
      case _ =>
        builder.build()
    }

    client.getConnectionStateListenable().addListener(ConnectionLostListener)

    client.start()
    client.blockUntilConnected(config.zkTimeoutDuration.toMillis.toInt, TimeUnit.MILLISECONDS)
    client
  }
}
