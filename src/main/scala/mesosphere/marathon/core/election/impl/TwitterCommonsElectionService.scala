package mesosphere.marathon.core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import com.twitter.common.base.{ ExceptionalCommand, Supplier }
import com.twitter.common.zookeeper.Candidate.{ Leader => TwitterCommonsLeader }
import com.twitter.common.zookeeper.Group.{ NodeScheme, JoinException }
import com.twitter.common.zookeeper.{ Candidate, Group, ZooKeeperClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.election.ElectionCallback
import mesosphere.marathon.metrics.Metrics
import org.apache.zookeeper.ZooDefs
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class TwitterCommonsElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  http: HttpConf,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  zk: ZooKeeperClient,
  electionCallbacks: Seq[ElectionCallback] = Seq.empty,
  backoff: Backoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  config, system, eventStream, metrics, electionCallbacks, backoff, shutdownHooks
) {
  private lazy val log = LoggerFactory.getLogger(getClass.getName)
  private lazy val commonsCandidate = provideCandidate(zk)

  override def leaderHostPortImpl: Option[String] = synchronized {
    val maybeLeaderData: Option[Array[Byte]] = try {
      Option(commonsCandidate.getLeaderData.orNull())
    }
    catch {
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
      stopLeadership()
    }

    override def onElected(abdicateCmd: ExceptionalCommand[JoinException]): Unit = synchronized {
      log.info("Elected (Leader Interface)")
      startLeadership(error => synchronized {
        abdicateCmd.execute()
        // stopLeadership() is called in onDefeated
      })
    }
  }

  private def provideCandidate(zk: ZooKeeperClient): Candidate = {
    log.info("Registering in ZooKeeper with hostPort:" + hostPort)
    new mesosphere.marathon.core.election.impl.CandidateImpl(
      new Group(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE, config.zooKeeperLeaderPath),
      new Supplier[Array[Byte]] {
        def get(): Array[Byte] = {
          hostPort.getBytes("UTF-8")
        }
      }
    )
  }
}
