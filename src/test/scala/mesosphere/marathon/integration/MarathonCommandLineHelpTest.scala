package mesosphere.marathon.integration

import java.io.File

import mesosphere.marathon.integration.setup.{ ProcessKeeper, IntegrationFunSuite }
import org.scalatest.{ GivenWhenThen, BeforeAndAfter, Matchers }

class MarathonCommandLineHelpTest
    extends IntegrationFunSuite
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  after {
    ProcessKeeper.stopAllProcesses()
  }

  test("marathon --help shouldn't crash") {
    val cwd = new File(".")
    val process = ProcessKeeper.startMarathon(
      cwd, env = Map.empty, arguments = List("--help"),
      startupLine = "Show help message")
    assert(process.exitValue() == 0)
  }
}
