package mesosphere.marathon
package core.election

import akka.actor.{ActorSystem, Cancellable}
import akka.event.EventStream
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.util.LifeCycledCloseable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ElectionModule(
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    hostPort: String,
    crashStrategy: CrashStrategy,
    electionEC: ExecutionContext
) {

  lazy private val electionBackend: Source[LeadershipState, Cancellable] = if (config.highlyAvailable()) {
    config.leaderElectionBackend.toOption match {
      case Some("curator") =>
        val client = new LifeCycledCloseable(CuratorElectionStream.newCuratorConnection(
          zkUrl = config.zooKeeperLeaderUrl,
          sessionTimeoutMs = config.zooKeeperSessionTimeout().toInt,
          connectionTimeoutMs = config.zooKeeperConnectionTimeout().toInt,
          timeoutDurationMs = config.zkTimeoutDuration.toMillis.toInt,
          defaultCreationACL = config.zkDefaultCreationACL
        ))
        sys.addShutdownHook { client.close() }
        CuratorElectionStream(
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

  lazy val service: ElectionService = new ElectionServiceImpl(eventStream, hostPort, electionBackend,
    crashStrategy, electionEC)(system)
}
