package mesosphere.marathon.integration

import java.io.File

import mesosphere.marathon.integration.setup._
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

class MarathonStartupIntegrationTest extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {
  test("Marathon should fail during start, if the HTTP port is already bound") {
    Given(s"a Marathon process already running on port ${config.marathonBasePort}")

    When("starting another Marathon process using an HTTP port that is already bound")
    val cwd = new File(".")
    val failingProcess = ProcessKeeper.startMarathon(
      cwd,
      env,
      List("--http_port", config.marathonBasePort.toString, "--zk", config.zk, "--master", config.master),
      startupLine = "Failed to start all services.",
      processName = "marathonFail"
    )

    Then("the new process should fail and exit with an error code")
    assert(failingProcess.exitValue() > 0)
  }
}
