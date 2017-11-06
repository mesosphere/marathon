package mesosphere.marathon
package integration

import akka.stream.scaladsl.{ Sink, Source, Tcp }
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.util.PortAllocator
import org.scalatest.concurrent.Eventually

@IntegrationTest
class MarathonStartupIntegrationTest extends AkkaIntegrationTest
    with MesosClusterTest
    with ZookeeperServerTest
    with MarathonFixture
    with Eventually {

  def withBoundPort(fn: Int => Unit): Unit = {
    val port = PortAllocator.ephemeralPort()
    val handler = Tcp().bind("127.0.0.1", port).to(Sink.foreach{ c =>
      Source.empty.via(c.flow).runWith(Sink.ignore)
    }).run.futureValue

    try fn(port)
    finally {
      handler.unbind()
    }
  }

  "Marathon" should {
    "fail during start, if the HTTP port is already bound" in withBoundPort { port =>
      Given(s"Some process already running on port ${port}")

      When("starting another Marathon process using an HTTP port that is already bound")

      val conflictingMarathon = LocalMarathon(true, s"$suiteName-conflict",
        mesosMasterUrl,
        s"zk://${zkServer.connectUri}/marathon-$suiteName",
        Map(
          "http_port" -> port.toString,
          "http_address" -> "127.0.0.1",
          "zk_timeout" -> "2000"))

      Then("The Marathon process should exit with code > 0")
      try {
        eventually {
          conflictingMarathon.isRunning() should be(false)
        } withClue ("The conflicting Marathon did not suicide.")
        conflictingMarathon.exitValue().get should be > 0 withClue (s"Conflicting Marathon exited with ${conflictingMarathon.exitValue()} instead of an error code > 0.")
      } finally {
        // Destroy process if it did not exit in time.
        conflictingMarathon.stop().futureValue
      }
    }
  }
}
