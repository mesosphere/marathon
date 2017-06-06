package mesosphere.marathon.core.election.impl

import com.typesafe.scalalogging.StrictLogging
import java.util
import java.util.Collections
import java.util.concurrent.{ Executors, TimeUnit }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base._
import mesosphere.marathon.metrics.Metrics
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.framework.{ AuthInfo, CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.{ RetryPolicy, RetrySleeper }
import org.apache.zookeeper.data.ACL
import org.apache.zookeeper.{ CreateMode, KeeperException, ZooDefs }
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

  system.registerOnTermination { () =>
    logger.debug("Abdicating on shutdown.")
    abdicateLeadership()
    client.close()
  }

  override def leaderHostPortImpl: Option[String] = synchronized {
    try {
      maybeLatch.flatMap { l =>
        val participant = l.getLeader
        if (participant.isLeader) Some(participant.getId) else None
      }
    } catch {
      case NonFatal(e) =>
        logger.error("error while getting current leader", e)
        None
    }
  }

  override def offerLeadershipImpl(): Unit = synchronized {
    logger.info("Using HA and therefore offering leadership")
    maybeLatch.foreach { l =>
      logger.info("Offering leadership while being candidate")
      l.close()
    }

    try {
      val latch = new LeaderLatch(client, config.zooKeeperLeaderPath + "-curator", hostPort, LeaderLatch.CloseMode.NOTIFY_LEADER)
      latch.addListener(Listener, callbackExecutor)
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
  private object Listener extends LeaderLatchListener {
    override def notLeader(): Unit = Future {
      logger.info(s"Leader defeated. New leader: ${leaderHostPort.getOrElse("-")}")

      // remove tombstone for twitter commons
      twitterCommonsTombstone.delete(onlyMyself = true)
      stopLeadership()
    }

    override def isLeader(): Unit = Future {
      logger.info("Leader elected")
      startLeadership(onAbdicate(_))

      // write a tombstone into the old twitter commons leadership election path which always
      // wins the selection. Check that startLeadership was successful and didn't abdicate.
      if (CuratorElectionService.this.isLeader) {
        twitterCommonsTombstone.create()
      }
    }
  }

  private[this] def onAbdicate(error: Boolean): Unit = synchronized {
    maybeLatch match {
      case None => logger.error(s"Abdicating leadership while not being leader (error: $error)")
      case Some(l) =>
        maybeLatch = None
        l.close()
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
          logger.error("ZooKeeper access failed - Committing suicide to avoid invalidating ZooKeeper state")
          suicide()
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

    client.start()
    client.blockUntilConnected(config.zkTimeoutDuration.toMillis.toInt, TimeUnit.MILLISECONDS)
    client
  }

  private[impl] def suicide(): Unit = {
    Runtime.getRuntime.asyncExit()(scala.concurrent.ExecutionContext.global)
  }

  private object twitterCommonsTombstone {
    def memberPath(member: String): String = {
      config.zooKeeperLeaderPath.stripSuffix("/") + "/" + member
    }

    // - precedes 0-9 in ASCII and hence this instance overrules other candidates
    lazy val memberName = "member_-00000000"
    lazy val path = memberPath(memberName)

    var fallbackCreated = false

    def create(): Unit = {
      try {
        delete(onlyMyself = false)

        client.createContainers(config.zooKeeperLeaderPath)

        // Create a ephemeral node which is not removed when loosing leadership. This is necessary to avoid a
        // race of old Marathon instances which think that they can become leader in the moment
        // the new instances failover and no tombstone is existing (yet).
        if (!fallbackCreated) {
          client.create().
            creatingParentsIfNeeded().
            withMode(CreateMode.EPHEMERAL_SEQUENTIAL).
            forPath(memberPath("member_-1"), hostPort.getBytes("UTF-8"))
          fallbackCreated = true
        }

        logger.info("Creating tombstone for old twitter commons leader election")
        client.create().
          creatingParentsIfNeeded().
          withMode(CreateMode.EPHEMERAL).
          forPath(path, hostPort.getBytes("UTF-8"))
      } catch {
        case NonFatal(e) =>
          logger.error(s"Exception while creating tombstone for twitter commons leader election: ${e.getMessage}")
          abdicateLeadership(error = true)
      }
    }

    @SuppressWarnings(Array("SwallowedException"))
    def delete(onlyMyself: Boolean = false): Unit = try {
      Option(client.checkExists().forPath(path)).foreach { tombstone =>
        try {
          if (!onlyMyself ||
            new String(client.getData.forPath(memberPath(memberName))) == hostPort) {
            logger.info("Deleting existing tombstone for old twitter commons leader election")
            client.delete().guaranteed().withVersion(tombstone.getVersion).forPath(path)
          }
        } catch {
          case _: KeeperException.NoNodeException =>
          case _: KeeperException.BadVersionException =>
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.error("Unexpected exception while deleting the old twitter Commons leader election tombstone; " +
          "Commit suicide", e)
        suicide()
    }
  }
}
