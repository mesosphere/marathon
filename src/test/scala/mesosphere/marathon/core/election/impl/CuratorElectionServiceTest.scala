package mesosphere.marathon
package core.election.impl

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{ RichRuntime, LifecycleState }
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import mesosphere.marathon.util.ScallopStub
import scala.concurrent.duration._

class CuratorElectionServiceTest extends AkkaUnitTest with Mockito with ExitDisabledTest {

  "The CuratorElectionService" when {

    val conf: MarathonConf = mock[MarathonConf]
    val eventStream: EventStream = mock[EventStream]
    val hostPort = "80"
    val backoff: ExponentialBackoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)

    val service = new CuratorElectionService(conf, system, eventStream, hostPort, backoff, LifecycleState.Ignore)

    "given an unresolvable hostname" should {

      conf.zkHosts returns "unresolvable:8080"
      conf.zooKeeperSessionTimeout returns ScallopStub(Some(10))
      conf.zooKeeperConnectionTimeout returns ScallopStub(Some(10))
      conf.zooKeeperTimeout returns ScallopStub(Some(10))
      conf.zkPath returns "/marathon"
      conf.zkSessionTimeoutDuration returns 10000.milliseconds
      conf.zkConnectionTimeoutDuration returns 10000.milliseconds
      conf.zkTimeoutDuration returns 250.milliseconds

      "shut Marathon down on a NonFatal" in {
        service.offerLeadershipImpl()

        exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
      }
    }
  }
}
