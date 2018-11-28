package mesosphere.marathon
package core.election

import akka.actor.{ActorSystem, Cancellable}
import akka.event.EventStream
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.LifeCycledCloseable
import org.apache.curator.framework.CuratorFramework

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ElectionModule(
    metrics: Metrics,
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    hostPort: String,
    crashStrategy: CrashStrategy,
    curatorFramework: CuratorFramework,
    electionEC: ExecutionContext
) {

  lazy private val electionBackend: Source[LeadershipState, Cancellable] = if (config.highlyAvailable()) {
    config.leaderElectionBackend.toOption match {
      case Some("curator") =>
        val client = new LifeCycledCloseable[CuratorFramework](curatorFramework)
        sys.addShutdownHook { client.close() }
        CuratorElectionStream(
          metrics,
          client,
          config.zooKeeperLeaderUrl.path,
          config.zooKeeperConnectionTimeout().millis,
          hostPort,
          electionEC)
      case backend: Option[String] =>
        throw new IllegalArgumentException(s"Leader election backend $backend not known!")
    }
  } else {
    PsuedoElectionStream()
  }

  lazy val service: ElectionService = new ElectionServiceImpl(metrics, eventStream, hostPort, electionBackend,
    crashStrategy, electionEC)(system)
}
