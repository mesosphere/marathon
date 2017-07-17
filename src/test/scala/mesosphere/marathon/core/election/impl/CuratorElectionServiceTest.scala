package mesosphere.marathon
package core.election.impl

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.{ CrashStrategy, LifecycleState }
import mesosphere.marathon.core.election.ElectionCandidate
import mesosphere.marathon.util.ScallopStub
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

class CuratorElectionServiceTest extends AkkaUnitTest with Eventually {
  "The CuratorElectionService" when {

    val conf: MarathonConf = mock[MarathonConf]
    val eventStream: EventStream = mock[EventStream]
    val hostPort = "80"

    val crashStrategy = mock[CrashStrategy]
    val service = new CuratorElectionService(conf, hostPort, system, eventStream, LifecycleState.Ignore, crashStrategy)

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
        val candidate = mock[ElectionCandidate]
        service.offerLeadership(candidate)
        eventually { verify(crashStrategy).crash() }
      }
    }
  }
}
