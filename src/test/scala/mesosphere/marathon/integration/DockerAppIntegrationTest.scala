package mesosphere.marathon
package integration

import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{ EmbeddedMarathonTest, MesosConfig }
import mesosphere.marathon.raml.{ App, Container, DockerContainer, EngineType, Network, NetworkMode }
import mesosphere.marathon.state.PathId._
import mesosphere.{ AkkaIntegrationTest, WhenEnvSet }

@IntegrationTest
class DockerAppIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(containerizers = "docker,mesos")

  // FIXME (gkleiman): Docker tests don't work under Docker Machine yet. So they can be disabled through an env variable.
  val envVar = "RUN_DOCKER_INTEGRATION_TESTS"

  //clean up state before running the test case
  after(cleanUp())

  def healthyDockerApp(testName: String, f: (App) => App = (x) => x) =
    testName taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new app")
      val app = f(dockerAppProxy(testBasePath / "docker-http-app", "v1", instances = 1, healthCheck = Some(appProxyHealthCheck())))
      val check = registerAppProxyHealthCheck(app.id.toPath, "v1", state = true)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      eventually {
        check.pinged.get should be(true) withClue "Docker app has not been pinged."
      }
    }

  "DockerApp" should {
    "deploy a simple Docker app" taggedAs WhenEnvSet(envVar, default = "true") in {
      Given("a new Docker app")
      val app = App(
        id = (testBasePath / "dockerapp").toString,
        cmd = Some("sleep 600"),
        container = Some(Container(`type` = EngineType.Docker, docker = Some(DockerContainer(image = "busybox")))),
        cpus = 0.2, mem = 16.0,
        instances = 1
      )

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) // The app has really started
    }

    behave like healthyDockerApp("create a simple docker app using http health checks with HOST networking")

    behave like healthyDockerApp(
      "create a simple docker app using http health checks with BRIDGE networking",
      (app) => app.copy(networks = Seq(Network(mode = NetworkMode.ContainerBridge)))
    )
  }
}
