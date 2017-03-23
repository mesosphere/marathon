package mesosphere.marathon
package integration

import java.io.File
import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{ App, AppHealthCheck, AppHealthCheckProtocol, AppUpdate, PortDefinition, ReadinessCheck, UpgradeStrategy }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually

@IntegrationTest
class ReadinessCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Eventually {

  //clean up state before running the test case
  after(cleanUp())

  "ReadinessChecks" should {
    "A deployment of an application with readiness checks (no health) does finish when the plan is ready" in {
      deploy(serviceProxy("/readynohealth".toTestPath, "phase(block1!,block2!,block3!)", withHealth = false), continue = true)
    }

    "A deployment of an application with readiness checks and health does finish when health checks succeed and plan is ready" in {
      deploy(serviceProxy("/readyhealth".toTestPath, "phase(block1!,block2!,block3!)", withHealth = true), continue = true)
    }

    "A deployment of an application without readiness checks and health does finish when health checks succeed" in {
      deploy(serviceProxy("/noreadyhealth".toTestPath, "phase()", withHealth = true), continue = false)
    }

    "A deployment of an application without readiness checks and without health does finish" in {
      deploy(serviceProxy("/noreadynohealth".toTestPath, "phase()", withHealth = false), continue = false)
    }

    "An upgrade of an application will wait for the readiness checks" in {
      val serviceDef = serviceProxy("/upgrade".toTestPath, "phase(block1!,block2!,block3!)", withHealth = false)
      deploy(serviceDef, continue = true)

      When("The service is upgraded")
      val oldTask = marathon.tasks(serviceDef.id.toPath).value.head
      val update = marathon.updateApp(serviceDef.id.toPath, AppUpdate(env = Some(raml.Environment(sys.env))), force = false)

      And("The ServiceMock is up")
      val serviceFacade = ServiceMockFacade(marathon.tasks(serviceDef.id.toPath).value) { task =>
        task.id != oldTask.id && task.launched
      }

      Then("The deployment does not succeed until the readiness checks succeed")
      while (serviceFacade.plan().code != 200) {
        When("We continue on block until the plan is ready")
        serviceFacade.continue()
        marathon.listDeploymentsForBaseGroup().value should have size 1
      }
      waitForDeployment(update)
    }
  }

  def deploy(service: App, continue: Boolean): Unit = {
    Given("An application service")
    val result = marathon.createAppV2(service)
    result.code should be (201) withClue (result.entityString)
    When("The ServiceMock is up")
    val serviceFacade = ServiceMockFacade(marathon.tasks(service.id.toPath).value)(_.launched)

    while (continue && serviceFacade.plan().code != 200) {
      When("We continue on block until the plan is ready")
      marathon.listDeploymentsForBaseGroup().value should have size 1
      serviceFacade.continue()
    }

    Then("The deployment should finish")
    waitForDeployment(result)
  }

  def serviceProxy(appId: PathId, plan: String, withHealth: Boolean): App = {
    App(
      id = appId.toString,
      cmd = Some(s"""$serviceMockScript '$plan'"""),
      executor = "//cmd",
      cpus = 0.01,
      upgradeStrategy = Some(UpgradeStrategy(0, 0)),
      portDefinitions = Some(Seq(PortDefinition(name = Some("http")))),
      healthChecks =
        if (withHealth)
          Set(AppHealthCheck(
          protocol = AppHealthCheckProtocol.Http,
          path = Some("/ping"),
          portIndex = Some(0),
          maxConsecutiveFailures = Int.MaxValue,
          intervalSeconds = 2,
          timeoutSeconds = 1
        ))
        else Set.empty,
      readinessChecks = Seq(ReadinessCheck(
        name = "ready",
        portName = "http",
        path = "/v1/plan",
        intervalSeconds = 2,
        timeoutSeconds = 1,
        preserveLastResponse = true))
    )
  }

  /**
    * Create a shell script that can start a service mock
    */
  private lazy val serviceMockScript: String = {
    val uuid = UUID.randomUUID.toString
    appProxyIds(_ += uuid)
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -DtestSuite=$suiteName -DappProxyId=$uuid -Xmx64m -classpath $classPath $main"""
    val file = File.createTempFile("serviceProxy", ".sh")
    file.deleteOnExit()

    FileUtils.write(
      file,
      s"""#!/bin/sh
          |set -x
          |exec $run $$*""".stripMargin)
    file.setExecutable(true)
    file.getAbsolutePath
  }
}
