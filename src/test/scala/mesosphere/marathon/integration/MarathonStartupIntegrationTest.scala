package mesosphere.marathon
package integration

import mesosphere.{ AkkaIntegrationTest, Unstable }
import mesosphere.marathon.integration.setup._

@IntegrationTest
class MarathonStartupIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {
  "Marathon" should {
    "fail during start, if the HTTP port is already bound" taggedAs (Unstable) in {
      Given(s"a Marathon process already running on port ${marathonServer.httpPort}")

      When("starting another Marathon process using an HTTP port that is already bound")

      val conflict = new MarathonApp(Seq("--master", marathonServer.masterUrl,
        "--zk", marathonServer.zkUrl, "--http_port", marathonServer.httpPort.toString))

      Then("An uncaught exception should be thrown")
      intercept[Throwable] {
        conflict.start()
      }
      conflict.close()
    }
  }
}
