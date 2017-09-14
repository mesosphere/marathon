package mesosphere.marathon.core.election.impl

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{ CrashStrategy, ShutdownHooks }
import mesosphere.marathon.core.election.ElectionCandidate
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import org.rogach.scallop.ScallopOption
import org.scalatest.concurrent.Eventually

class CuratorElectionServiceTest extends AkkaUnitTest with Mockito with ExitDisabledTest {

  def scallopOption[A](a: Option[A]): ScallopOption[A] = {
    new ScallopOption[A]("") {
      override def get = a
      override def apply() = a.get
    }
  }

  "The CuratorElectionService" when {

    val conf: MarathonConf = mock[MarathonConf]
    val eventStream: EventStream = mock[EventStream]
    val metrics: Metrics = mock[Metrics]
    val hostPort = "80"
    val shutdownHooks: ShutdownHooks = mock[ShutdownHooks]
    val crashStrategy: CrashStrategy = mock[CrashStrategy]

    val service = new CuratorElectionService(conf, hostPort, system, eventStream, metrics, shutdownHooks, crashStrategy)

    "given an unresolvable hostname" should {

      conf.zkHosts returns "unresolvable:8080"
      conf.zooKeeperSessionTimeout returns scallopOption(Some(10))
      conf.zooKeeperTimeout returns scallopOption(Some(10))
      conf.zkPath returns "/marathon"

      "shut Marathon down on a NonFatal" in {
        val candidate = mock[ElectionCandidate]
        service.offerLeadership(candidate)
        Eventually.eventually { verify(crashStrategy).crash() }
      }
    }
  }
}
