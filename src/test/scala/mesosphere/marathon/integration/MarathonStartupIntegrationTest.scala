package mesosphere.marathon
package integration

import java.nio.file.Files
import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.TimeLimits
import org.scalatest.time.{ Seconds, Span }

import scala.sys.process.Process

class MarathonStartupIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with TimeLimits {

  "Marathon" should {
    "fail during start, if the HTTP port is already bound" in {
      Given(s"a Marathon process already running on port ${marathonServer.httpPort}")

      When("starting another Marathon process using an HTTP port that is already bound")

      val args = Seq(
        "--master", marathonServer.masterUrl,
        "--zk", marathonServer.zkUrl,
        "--http_port", marathonServer.httpPort.toString,
        "--zk_timeout", "2000"
      )

      val mainClass = "mesosphere.marathon.Main"
      val workDir = Files.createTempDirectory(s"marathon-${marathonServer.httpPort}-conflict").toFile
      workDir.deleteOnExit()
      val uuid = UUID.randomUUID.toString
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val cp = sys.props.getOrElse("java.class.path", "target/classes")
      val memSettings = s"-Xmx${Runtime.getRuntime.maxMemory()}"
      val cmd = Seq(java, memSettings, s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-classpath", cp, mainClass) ++ args
      val marathonProcess = Process(cmd, workDir, sys.env.toSeq: _*)
        .run(ProcessOutputToLogStream(s"$suiteName-LocalMarathon-${marathonServer.httpPort}-conflict"))

      Then("The Marathon process should exit with code > 0")
      try {
        failAfter(Span(40, Seconds)) {
          marathonProcess.exitValue() should be > 0
        }
      } finally {
        // Destroy process if it did not exit in time.
        marathonProcess.destroy()
      }
    }
  }
}
