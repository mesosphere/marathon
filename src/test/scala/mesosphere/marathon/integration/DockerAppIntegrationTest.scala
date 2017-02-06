package mesosphere.marathon
package integration

import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{ EmbeddedMarathonTest, MesosConfig }
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ AppDefinition, Container }
import mesosphere.{ AkkaIntegrationTest, WhenEnvSet }

import scala.concurrent.duration._

@IntegrationTest
class DockerAppIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(containerizers = "docker,mesos")

  // FIXME (gkleiman): Docker tests don't work under Docker Machine yet. So they can be disabled through an env variable.
  val envVar = "RUN_DOCKER_INTEGRATION_TESTS"

  //clean up state before running the test case
  after(cleanUp())

  "DockerApp" should {
    "deploy a simple Docker app" taggedAs WhenEnvSet(envVar) in {
      Given("a new Docker app")
      val app = AppDefinition(
        id = testBasePath / "dockerapp",
        cmd = Some("sleep 600"),
        container = Some(Container.Docker(image = "busybox")),
        resources = Resources(cpus = 0.2, mem = 16.0),
        instances = 1
      )

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result.code should be(201) // Created
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id, 1) // The app has really started
    }

    "create a simple docker app using http health checks with HOST networking" taggedAs WhenEnvSet(envVar) in {
      Given("a new app")
      val app = dockerAppProxy(testBasePath / "docker-http-app", "v1", instances = 1, healthCheck = Some(appProxyHealthCheck()))
      val check = appProxyCheck(app.id, "v1", true)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result.code should be(201) //Created
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      check.pingSince(5.seconds) should be(true) //make sure, the app has really started
    }
  }
}
