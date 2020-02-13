package mesosphere.marathon
package integration

import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig, ProcessOutputToLogStream}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType, Network, NetworkMode}
import mesosphere.marathon.state.PathId._
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}

import scala.sys.process.Process

class DockerAppIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(containerizers = "docker,mesos")

  // FIXME (gkleiman): Docker tests don't work under Docker Machine yet. So they can be disabled through an env variable.
  val envVar = "RUN_DOCKER_INTEGRATION_TESTS"

  def waitForDocker(retries: Int): Unit = {
    val timeoutSeconds = 5 // Mesos agent waits 5 seconds
    val p = Process(command = Seq("timeout", (timeoutSeconds * 1000).toString, "docker", "-H", "/var/run/docker.sock", "--version"))

    val result = p.!(ProcessOutputToLogStream(s"$suiteName-WaitForDocker"))
    if (result != 0) {
      logger.info("docker --version failed; not responsive?")
      if (retries > 0)
        waitForDocker(retries - 1)
      else
        assert(false, "Cannot run integration test. Docker API is not responsive.")
    }
  }

  override def beforeAll(): Unit = {
    waitForDocker(10)
    super.beforeAll()
  }

  // This suite would sometimes time out in `beforeAll` method because mesos agent would crash during docker initialisation
  // with:
  // ```
  // [main.cpp:498] EXIT with status 1: Failed to create a containerizer: Could not create DockerContainerizer:
  // Failed to create docker: Timed out getting docker version
  // ```
  // Currently it is not possible to change the waiting duration:
  // https://github.com/mesosphere/mesos/blob/master/src/slave/constants.hpp#L144

  // and it's questionable whether it would be the solution - it's is possible that docker client/daemon is simply
  // hanging. I guess we have to leave with this flake for the time being.
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
        id = (testBasePath / "simple-docker-app").toString,
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

    behave like healthyDockerApp(
      "create a simple docker app using http health checks with HOST networking",
      (app) => app.copy(id = (testBasePath / "docker-http-app-with-host-networking").toString)
    )

    behave like healthyDockerApp(
      "create a simple docker app using http health checks with BRIDGE networking",
      (app) => app.copy(
        id = (testBasePath / "docker-http-app-with-bridge-networking").toString,
        networks = Seq(Network(mode = NetworkMode.ContainerBridge)))
    )
  }
}
