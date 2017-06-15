package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{ AppHealthCheck, AppHealthCheckProtocol, AppUpdate, PortDefinition, ReadinessCheck }
import mesosphere.marathon.state.PathId
import org.scalatest.concurrent.Eventually

@IntegrationTest
class ReadinessCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Eventually {

  //clean up state before running the test case
  before(cleanUp())

  private val ramlHealthCheck = AppHealthCheck(
    protocol = AppHealthCheckProtocol.Http,
    gracePeriodSeconds = 20,
    intervalSeconds = 1,
    maxConsecutiveFailures = Int.MaxValue,
    portIndex = Some(0),
    delaySeconds = 2
  )

  private val ramlReadinessCheck = ReadinessCheck(
    name = "ready",
    portName = "http",
    path = "/ready",
    intervalSeconds = 2,
    timeoutSeconds = 1,
    preserveLastResponse = true
  )

  "ReadinessChecks" should {
    "A deployment of an application with readiness checks (no health) does finish when the app is ready" in {

      Given("An application service")
      val app = appProxy("/readynohealth".toTestPath, "v1", instances = 1, healthCheck = None)
        .copy(
          portDefinitions = Some(Seq(PortDefinition(name = Some("http")))),
          readinessChecks = Seq(ramlReadinessCheck)
        )

      And("The app is not ready")
      val readinessCheck = registerProxyReadinessCheck(PathId(app.id), "v1")
      readinessCheck.isReady.set(false)

      When("The app is created")
      val result = marathon.createAppV2(app)
      result should be (Created)

      (1 to 3).foreach { _ =>

        Then("The readiness check is called")
        readinessCheck.wasCalled.set(false)
        eventually {
          readinessCheck.wasCalled.get should be(true)
        }

        And("There is one ongoing deployment")
        val deployments = marathon.listDeploymentsForBaseGroup().value
        deployments should have size 1 withClue (s"Expected 1 deployment but found ${deployments}")
      }

      When("The app is ready")
      readinessCheck.isReady.set(true)

      Then("The deployment should finish")
      waitForDeployment(result)
    }

    "A deployment of an application with readiness checks and health does finish when health checks succeed and plan is ready" in {
      Given("An application service")
      val app = appProxy("/readyhealth".toTestPath, "v1", instances = 1, healthCheck = None)
        .copy(
          healthChecks = Set(ramlHealthCheck),
          portDefinitions = Some(Seq(PortDefinition(name = Some("http")))),
          readinessChecks = Seq(ramlReadinessCheck)

        )

      And("The app is not ready and not healthy")
      //TODO start with state - false
      val check = registerAppProxyHealthCheck(PathId(app.id), "v1", state = true)
      val readinessCheck = registerProxyReadinessCheck(PathId(app.id), "v1")
      readinessCheck.isReady.set(false)

      When("The app is created")
      val result = marathon.createAppV2(app)
      result should be(Created)

      (1 to 3).foreach { _ =>

        Then("The readiness check is called")
        readinessCheck.wasCalled.set(false)
        eventually {
          readinessCheck.wasCalled.get should be(true)
        }

        And("There is one ongoing deployment")
        val deployments = marathon.listDeploymentsForBaseGroup().value
        deployments should have size 1 withClue (s"Expected 1 deployment but found ${deployments}")
      }

      When("The app is ready and healthy")
      readinessCheck.isReady.set(true)

      Then("The deployment should finish")
      waitForDeployment(result)
    }

    "An upgrade of an application will wait for the readiness checks" in {

      Given("An application service")
      val appV1 = appProxy("/readyhealth".toTestPath, "v1", instances = 1, healthCheck = None)
        .copy(
          portDefinitions = Some(Seq(PortDefinition(name = Some("http")))),
          readinessChecks = Seq(ramlReadinessCheck)

        )
      And("The app is not ready")
      val readinessCheckV1 = registerProxyReadinessCheck(PathId(appV1.id), "v1")
      readinessCheckV1.isReady.set(true)

      When("The app is created")
      val result = marathon.createAppV2(appV1)
      result should be (Created)

      And("There is one ongoing deployment")
      val deployments = marathon.listDeploymentsForBaseGroup().value
      deployments should have size 1 withClue (s"Expected 1 deployment but found ${deployments}")

      Then("The app is deployed")
      waitForDeployment(result)

      When("The service is upgraded and the upgrade is not ready")
      val readinessCheckV2 = registerProxyReadinessCheck(appV1.id.toTestPath, "v2")
      readinessCheckV2.isReady.set(false)
      val update = marathon.updateApp(PathId(appV1.id), AppUpdate(cmd = appProxy(appV1.id.toTestPath, "v2", 1).cmd))
      update.success should be(true) withClue (update.entityString)

      (1 to 3).foreach { _ =>

        Then("The readiness check is called")
        readinessCheckV2.wasCalled.set(false)
        eventually {
          readinessCheckV2.wasCalled.get should be(true)
        }

        And("There is one ongoing deployment")
        val deployments = marathon.listDeploymentsForBaseGroup().value
        deployments should have size 1 withClue (s"Expected 1 deployment but found ${deployments}")
      }

      When("The upgraded app is ready and healthy")
      readinessCheckV2.isReady.set(true)

      Then("The update should finish")
      waitForDeployment(update)
    }
  }

}
