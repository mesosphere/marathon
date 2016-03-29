package mesosphere.marathon.core.leadership

import java.lang.Boolean
import java.net.InetAddress

import com.google.common.base.{Optional, Supplier}
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Candidate.Leader
import com.twitter.common.zookeeper.{Candidate, Group}
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.slf4j.LoggerFactory

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
