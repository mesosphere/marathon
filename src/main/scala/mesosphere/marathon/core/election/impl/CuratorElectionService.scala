package mesosphere.marathon.core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import com.twitter.common.zookeeper.{ ZooKeeperUtils, Group, ZooKeeperClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.election.ElectionCallback
import mesosphere.marathon.metrics.Metrics
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.{ ZooDefs, KeeperException, CreateMode }
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class CuratorElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  http: HttpConf,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  zk: ZooKeeperClient,
  electionCallbacks: Seq[ElectionCallback] = Seq.empty,
  backoff: ExponentialBackoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  config, system, eventStream, metrics, electionCallbacks, backoff, shutdownHooks
) {
  private lazy val log = LoggerFactory.getLogger(getClass.getName)

  private lazy val maxZookeeperBackoffTime = 1000 * 300
  private lazy val minZookeeperBackoffTime = 500

  private lazy val curatorFramework = provideCuratorClient(zk)
  private var latch: Option[LeaderLatch] = None

  override def leaderHostPortImpl: Option[String] = synchronized {
    try {
      latch match {
        case None => None
        case Some(l) =>
          val participant = l.getLeader
          if (participant.isLeader) Some(participant.getId) else None
      }
    }
    catch {
      case NonFatal(e) =>
        log.error("error while getting current leader", e)
        None
    }
  }

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Using HA and therefore offering leadership")
    latch match {
      case Some(l) =>
        log.error("Offering leadership while being candidate")
        l.close()
      case _ =>
    }
    latch = Some(new LeaderLatch(
      curatorFramework, config.zooKeeperLeaderPath + "-curator", hostPort, LeaderLatch.CloseMode.NOTIFY_LEADER
    ))
    latch.get.addListener(Listener) // idem-potent
    latch.get.start()
  }

  private object Listener extends LeaderLatchListener {
    override def notLeader(): Unit = CuratorElectionService.this.synchronized {
      log.info(s"Defeated (LeaderLatchListener Interface). New leader: ${leaderHostPort.getOrElse("-")}")

      // remove tombstone for twitter commons
      twitterCommonsTombstone.delete(onlyMyself = true)

      stopLeadership()
    }

    override def isLeader(): Unit = CuratorElectionService.this.synchronized {
      log.info("Elected (LeaderLatchListener Interface)")
      startLeadership(error => CuratorElectionService.this.synchronized {
        latch match {
          case None => log.error("Abdicating leadership while not being leader")
          case Some(l) =>
            latch = None
            l.close()
        }
        // stopLeadership() is called in notLeader
      })

      // write a tombstone into the old twitter commons leadership election path which always
      // wins the selection. Check that startLeadership was successful and didn't abdicate.
      if (CuratorElectionService.this.isLeader) {
        twitterCommonsTombstone.create()
      }
    }
  }

  private def provideCuratorClient(zk: ZooKeeperClient): CuratorFramework = {
    val client = CuratorFrameworkFactory.newClient(zk.getConnectString,
      new ExponentialBackoffRetry(minZookeeperBackoffTime, maxZookeeperBackoffTime))
    client.start()
    client.getZookeeperClient.blockUntilConnectedOrTimedOut()
    client
  }

  private object twitterCommonsTombstone {
    lazy val acl = ZooDefs.Ids.OPEN_ACL_UNSAFE
    lazy val group = new Group(zk, acl, config.zooKeeperLeaderPath)

    // - precedes 0-9 in ASCII and hence this instance overrules other candidates
    lazy val memberName = "member_-00000000"
    lazy val path = group.getMemberPath(memberName)

    var fallbackCreated = false

    def create(): Unit = {
      try {
        delete(onlyMyself = false)
        ZooKeeperUtils.ensurePath(zk, acl, config.zooKeeperLeaderPath)

        // Create a ephemeral node which is not removed when loosing leadership. This is necessary to avoid a
        // race of old Marathon instances which think that they can become leader in the moment
        // the new instances failover and no tombstone is existing (yet).
        if (!fallbackCreated) {
          zk.get.create(group.getMemberPath("member_-1"),
            hostPort.getBytes("UTF-8"),
            acl,
            CreateMode.EPHEMERAL_SEQUENTIAL
          )
          fallbackCreated = true
        }

        log.info("Creating tombstone for old twitter commons leader election")
        zk.get.create(path, hostPort.getBytes("UTF-8"), acl, CreateMode.EPHEMERAL)
      }
      catch {
        case e: Exception =>
          log.error(s"Exception while creating tombstone for twitter commons leader election: ${e.getMessage}")
          abdicateLeadership(error = true)
      }
    }

    def delete(onlyMyself: Boolean = false): Unit = {
      Option(zk.get.exists(path, false)) match {
        case None =>
        case Some(tombstone) =>
          try {
            if (!onlyMyself || group.getMemberData(memberName).toString == hostPort) {
              log.info("Deleting existing tombstone for old twitter commons leader election")
              zk.get.delete(path, tombstone.getVersion)
            }
          }
          catch {
            case _: KeeperException.NoNodeException     =>
            case _: KeeperException.BadVersionException =>
          }
      }
    }
  }
}
