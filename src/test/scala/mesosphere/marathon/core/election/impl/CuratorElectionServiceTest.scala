package mesosphere.marathon.core.election.impl

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{ RichRuntime, ShutdownHooks }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import org.rogach.scallop.ScallopOption

import scala.concurrent.duration._

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
    val backoff: ExponentialBackoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
    val shutdownHooks: ShutdownHooks = mock[ShutdownHooks]

    val service = new CuratorElectionService(conf, system, eventStream, metrics, hostPort, backoff, shutdownHooks)

    "given an unresolvable hostname" should {

      conf.zkHosts returns "unresolvable:8080"
      conf.zooKeeperSessionTimeout returns scallopOption(Some(10))
      conf.zooKeeperTimeout returns scallopOption(Some(10))
      conf.zkPath returns "/marathon"

      "shut Marathon down on a NonFatal" in {
        service.offerLeadershipImpl()

        exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
      }
    }
  }
}
