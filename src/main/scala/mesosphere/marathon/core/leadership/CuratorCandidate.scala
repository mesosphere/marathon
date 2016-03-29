package mesosphere.marathon.core.leadership

import java.lang.Boolean
import java.net.InetAddress

import org.apache.zookeeper.{ WatchedEvent, Watcher, CreateMode }

import scala.collection.JavaConversions._

import com.google.common.base.{ Optional, Supplier }
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Candidate.Leader
import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.{ CandidateImpl, Group, ZooKeeperClient, Candidate }
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.slf4j.LoggerFactory

class BackwardsCompatibleCuratorCandidate(
    zk: ZooKeeperClient,
    pathV2: String,
    compatiblityGroup: Group,
    id: String) extends Candidate {

  private val log = LoggerFactory.getLogger(getClass.getName)
  private val v2Prefix = "v2-"
  private var candidate: Either[Candidate, Candidate] = Left(
    new CandidateImpl(compatiblityGroup, new Supplier[Array[Byte]] {
      def get(): Array[Byte] = {
        (v2Prefix + id).getBytes("UTF-8")
      }
    })
  )

  private def oldMembersLeft: Boolean = {
    compatiblityGroup.getMemberIds.toList.exists(!_.startsWith(v2Prefix))
  }

  override def offerLeadership(leader: Leader): Supplier[Boolean] = {
    if (candidate.isLeft && Option(zk.get.exists(pathV2, false)).isDefined) {
      candidate = Right(new CuratorCandidate(zk.getConnectString, pathV2, id))
    }

    candidate match {
      case Left(c) => c.offerLeadership(new Leader {
        override def onDefeated(): Unit = {
          leader.onDefeated()
        }
        override def onElected(abdicate: ExceptionalCommand[JoinException]): Unit = {
          if (oldMembersLeft) {
            log.info("Got elected with leader election protocol v1. Old members still exist. Staying with v1.")
            leader.onElected(abdicate)
          }
          else {
            // no v1 member in the list, switch to v2 and restart election
            try {
              log.info("Switching to leader election protocol v2")
              zk.get.create(pathV2, Array[Byte](), Nil, CreateMode.EPHEMERAL_SEQUENTIAL)
            }
            catch {
              case e: Exception =>
                log.error("Failed to switch to leader election protocol v2")
                return leader.onElected(abdicate) //scalastyle:off return
            }
            abdicate.execute()
          }
        }
      })
      case Right(c) => c.offerLeadership(leader)
    }
  }

  override def getLeaderData: Optional[Array[Byte]] = {
    Optional.absent()
  }
}

class CuratorCandidate(
    zkConnectString: String,
    path: String,
    id: String) extends Candidate {
  val MaxZookeeperBackoffTime = 1000 * 300
  val MinZookeeperBackoffTime = 500

  private val log = LoggerFactory.getLogger(getClass.getName)

  private val client = CuratorFrameworkFactory.newClient(zkConnectString,
    new ExponentialBackoffRetry(MinZookeeperBackoffTime, MaxZookeeperBackoffTime))
  client.start()
  client.getZookeeperClient.blockUntilConnectedOrTimedOut()

  private var latch: Option[LeaderLatch] = None

  def this(zkConnectString: String, path: String) = {
    this(zkConnectString, path, InetAddress.getLocalHost.getHostAddress)
  }

  override def offerLeadership(leader: Leader): Supplier[Boolean] = {
    this.synchronized {
      latch.foreach({ l =>
        l.close()
      })
      var wasLeader = false
      latch = Some(new LeaderLatch(client, path, id))

      latch.foreach { l =>
        l.addListener(new LeaderLatchListener {
          override def isLeader(): Unit = latch.synchronized {
            latch.foreach { l =>
              if (!wasLeader) {
                log.info(s"Candidate $id is now leader of group: ${l.getParticipants}")

                leader.onElected(new ExceptionalCommand[Group.JoinException] {
                  override def execute(): Unit = {
                    l.close()
                    latch = None
                  }
                })
              }
              wasLeader = true
            }
          }

          override def notLeader(): Unit = latch.synchronized {
            latch.foreach { l =>
              if (wasLeader) {
                leader.onDefeated()
                log.info(s"Candidate $id waiting for the next leader election, current voting: ${l.getParticipants}")
              }
              wasLeader = false
            }
          }
        })
        l.start()
      }
      new Supplier[Boolean] {
        override def get(): Boolean = this.synchronized {
          latch.exists(_.hasLeadership)
        }
      }
    }
  }

  override def getLeaderData: Optional[Array[Byte]] = {
    Optional.absent()
  }
}

object BackwardsCompatible {
  private val log = LoggerFactory.getLogger(getClass.getName)

  def createCandidate(
    zk: ZooKeeperClient,
    pathV2: String,
    compatiblityGroup: Group,
    id: String): Candidate = {
    if (Option(zk.get.exists(pathV2, false)).isDefined ||
      Option(zk.get.exists(compatiblityGroup.getPath, false)).isEmpty) {
      log.info("Starting with leader election protocol v2")
      new CuratorCandidate(zk.getConnectString, pathV2, id)
    }
    else {
      log.info("Starting with leader election protocol v1")
      new BackwardsCompatibleCuratorCandidate(zk, pathV2, compatiblityGroup, id)
    }
  }
}